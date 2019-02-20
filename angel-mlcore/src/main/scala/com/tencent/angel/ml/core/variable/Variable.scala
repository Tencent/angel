package com.tencent.angel.ml.core.variable

import java.lang.{Long => JLong}
import java.util.concurrent.Future
import java.util.concurrent.locks.ReentrantReadWriteLock
import java.util.{HashMap => JHashMap, Map => JMap}

import com.tencent.angel.ml.core.conf.SharedConf
import com.tencent.angel.ml.core.network.{EvnContext, Graph}
import com.tencent.angel.ml.core.utils.NotInitialException
import com.tencent.angel.ml.core.variable.VarState.VarState
import com.tencent.angel.ml.math2.matrix.Matrix
import com.tencent.angel.ml.math2.utils.RowType
import com.tencent.angel.ml.math2.vector
import com.tencent.angel.ml.math2.vector.Vector

import scala.language.implicitConversions

object VarState extends Enumeration {
  type VarState = Value
  val New, Created, Initialized, Ready, Expired = Value
}

trait VoidType

trait Updater extends Serializable {
  val numSlot: Int

  def update[T](variable: Variable, epoch: Int, batchSize: Int): Future[T]
}

trait TrainCycle {
  protected var state: VarState = VarState.New
  protected val lock = new ReentrantReadWriteLock()
  protected val readLock: ReentrantReadWriteLock.ReadLock = lock.readLock()
  protected val writeLock: ReentrantReadWriteLock.WriteLock = lock.writeLock()

  def create(envCtx: EvnContext = null): Unit

  def load(envCtx: EvnContext, path: String): Unit

  def init(taskFlag: Int, mean: Double, stddev: Double): Unit

  def pull(epoch: Int, indices: vector.Vector = null): Unit

  def push(grad: Matrix, alpha: Double): Unit

  def update[T](epoch: Int, batchSize: Int): Future[T]

  def save(envCtx: EvnContext, path: String): Unit

  protected def transSate(from: VarState, to: VarState): Unit = {
    assert(state == from)
    state = to
  }
}

trait MatTrainCycle extends TrainCycle {
  protected var matrix: Matrix

  def snapshot(): Matrix = {
    assert(state == VarState.Ready)

    readLock.lock()
    try {
      if (matrix == null) {
        throw NotInitialException("the matrix is not initialized!")
      }

      matrix
    } finally {
      readLock.unlock()
    }
  }
}

trait VecTrainCycle extends TrainCycle {
  protected var vector: Vector

  def snapshot(): Vector = {
    assert(state == VarState.Ready)

    readLock.lock()
    try {
      if (vector == null) {
        throw NotInitialException("the vector is not initialized!")
      }

      vector
    } finally {
      readLock.unlock()
    }

  }
}


trait MatVariable extends MatTrainCycle

object MatVariable {
  implicit def toMatrix(v: MatVariable): Matrix = v.snapshot()
}


trait EmbedVariable extends MatVariable {
  protected val embeddings: JMap[JLong, Vector] = new JHashMap[JLong, Vector]()
}

object EmbedVariable {
  implicit def toMatrix(v: EmbedVariable): Matrix = v.snapshot()
}


trait BlasMatVariable extends MatVariable

object BlasMatVariable {
  implicit def toMatrix(v: BlasMatVariable): Matrix = v.snapshot()
}


trait VecVariable extends VecTrainCycle

object VecVariable {
  implicit def toVector(v: VecVariable): Vector = v.snapshot()
}


abstract class Variable(val name: String, val rowType: RowType, val updater: Updater,
                        val formatClassName: String, val allowPullWithIndex: Boolean)(implicit val graph: Graph)
  extends TrainCycle {
  graph.addVariable(this)
  protected var mean: Double = 0.0
  protected var stddev: Double = 0.000001
  protected val conf: SharedConf = SharedConf.get()
  protected lazy val normal: Double = graph.normalFactor

  protected val numSlot: Int = if (updater == null) {
    0
  } else {
    updater.numSlot
  }

  protected def doCreate(envCtx: EvnContext): Unit

  override def create(envCtx: EvnContext): Unit = {
    writeLock.lock()

    try {
      if (state == VarState.New || state == VarState.Expired) {
        doCreate(envCtx)
        // trans state
        if (state == VarState.New) {
          transSate(VarState.New, VarState.Created)
        } else {
          transSate(VarState.Expired, VarState.Created)
        }
      }
      doCreate(envCtx)
      assert(state == VarState.Created)
    } finally {
      writeLock.unlock()
    }
  }

  protected def doInit(taskFlag: Int): Unit

  override def init(taskFlag: Int, mean: Double, stddev: Double): Unit = {
    writeLock.lock()

    try {
      this.mean = mean
      this.stddev = stddev

      if (state == VarState.Created) {
        doInit(taskFlag)

        // trans stats
        transSate(VarState.Created, VarState.Initialized)
      }

      assert(state == VarState.Initialized)
    } finally {
      writeLock.unlock()
    }
  }

  protected def doLoad(envCtx: EvnContext, path: String): Unit

  override def load(envCtx: EvnContext, path: String): Unit = {
    writeLock.lock()

    try {
      if (state == VarState.New || state == VarState.Expired) {
        doLoad(envCtx, path)

        // trans state
        if (state == VarState.New) {
          transSate(VarState.New, VarState.Initialized)
        } else {
          transSate(VarState.Expired, VarState.Initialized)
        }
      }

      assert(state == VarState.Initialized)
    } finally {
      writeLock.unlock()
    }
  }

  protected def doPull(epoch: Int, indices: Vector): Unit

  override def pull(epoch: Int, indices: Vector): Unit = {
    writeLock.lock()

    try {
      if (state == VarState.Initialized || state == VarState.Created || state == VarState.Ready) {
        doPull(epoch, indices)

        // trans state
        if (state == VarState.Initialized) {
          transSate(VarState.Initialized, VarState.Ready)
        } else if (state == VarState.Created) {
          transSate(VarState.Created, VarState.Ready)
        }
      }

      assert(state == VarState.Ready)
    } finally {
      writeLock.unlock()
    }
  }

  protected def doPush(grad: Matrix, alpha: Double): Unit

  override def push(grad: Matrix, alpha: Double): Unit = {
    writeLock.lock()

    try {
      assert(state == VarState.Ready)

      doPush(grad, alpha)
    } finally {
      writeLock.unlock()
    }
  }

  protected def doSave(envCtx: EvnContext, path: String): Unit

  override def save(envCtx: EvnContext, path: String): Unit = {
    readLock.lock()

    try {
      assert(state == VarState.Initialized || state == VarState.Ready)
      doSave(envCtx, path)
    } finally {
      readLock.unlock()
    }
  }

  override def update[T](epoch: Int, batchSize: Int): Future[T] = {
    writeLock.lock()

    try {
      assert(state == VarState.Ready)

      if (numSlot > 0) {
        updater.update[T](this, epoch, batchSize)
      } else {
        null.asInstanceOf[Future[T]]
      }
    } finally {
      writeLock.unlock()
    }
  }
}