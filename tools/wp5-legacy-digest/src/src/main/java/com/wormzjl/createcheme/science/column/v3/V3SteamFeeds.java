package com.wormzjl.createcheme.science.column.v3;

/** Shared free-water arithmetic. Water is a known vapor profile, not a MESH state component. */
final class V3SteamFeeds {
    private V3SteamFeeds() {}

    static double[] nodeFeedFlows(V3ColumnInput input, V3ColumnTopology topology) {
        double[] feeds = new double[topology.nodeCount()];
        for (V3SteamFeedSpec feed : input.steamFeeds()) {
            feeds[feed.stageNumber()] = feed.molarFlowMolPerSecond();
        }
        return feeds;
    }

    static double[] upwardVaporProfile(double[] nodeFeedFlows, V3ColumnTopology topology) {
        if (nodeFeedFlows.length != topology.nodeCount()) {
            throw new IllegalArgumentException("V3 steam-feed profile does not match the topology");
        }
        double[] profile = new double[nodeFeedFlows.length];
        double upward = 0.0;
        for (int node = topology.reboilerNode(); node >= 1; node--) {
            upward += nodeFeedFlows[node];
            profile[node] = upward;
        }
        return profile;
    }

    static boolean hasSumpFeed(V3ColumnInput input) {
        return input.steamFeeds().stream().anyMatch(feed -> feed.stageNumber() == input.stageCount() + 1);
    }
}
