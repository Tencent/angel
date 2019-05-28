package com.tencent.angel.graph.client.sampleneighbor;

import com.tencent.angel.ml.matrix.psf.get.base.GetParam;

public class SampleNeighborParam extends GetParam {
    /**
     * Node ids the need get neighbors
     */
    private long[] nodeIds;

    /**
     * Edge types
     */
    private int[] edgeTypes;

    public SampleNeighborParam(int matrixId, long[] nodeIds, int[] edgeTypes) {
        super(matrixId);
        this.nodeIds = nodeIds;
        this.edgeTypes = edgeTypes;
    }

    public long[] getNodeIds() {
        return nodeIds;
    }

    public int[] getEdgeTypes() {
        return edgeTypes;
    }

}
