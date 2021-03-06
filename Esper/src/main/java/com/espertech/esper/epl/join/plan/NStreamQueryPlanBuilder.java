/**************************************************************************************
 * Copyright (C) 2008 EsperTech, Inc. All rights reserved.                            *
 * http://esper.codehaus.org                                                          *
 * http://www.espertech.com                                                           *
 * ---------------------------------------------------------------------------------- *
 * The software in this package is published under the terms of the GPL license       *
 * a copy of which has been included with this distribution in the license.txt file.  *
 **************************************************************************************/
package com.espertech.esper.epl.join.plan;

import com.espertech.esper.client.EventType;
import com.espertech.esper.collection.NumberSetPermutationEnumeration;
import com.espertech.esper.collection.NumberSetShiftGroupEnumeration;
import com.espertech.esper.collection.Pair;
import com.espertech.esper.epl.join.base.HistoricalViewableDesc;
import com.espertech.esper.epl.join.table.HistoricalStreamIndexList;
import com.espertech.esper.util.DependencyGraph;
import com.espertech.esper.util.JavaClassHelper;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.*;

/**
 *
 2 Stream query strategy/execution tree
 (stream 0)         Lookup in stream 1
 (stream 1)         Lookup in stream 0

 * ------ Example 1   a 3 table join
 *
 *          " where streamA.id = streamB.id " +
            "   and streamB.id = streamC.id";

 => Index propery names for each stream
    for stream 0 to 4 = "id"

 => join order, ie.
    for stream 0 = {1, 2}
    for stream 1 = {factor [0,2]}
    for stream 2 = {1, 0}

 => IndexKeyGen optionalIndexKeyGen, created by nested query plan nodes


 3 Stream query strategy
 (stream 0)          Nested iteration
    Lookup in stream 1        Lookup in stream 2

 (stream 1)         Factor
    Lookup in stream 0        Lookup in stream 2

 (stream 2)         Nested iteration
    Lookup in stream 1        Lookup in stream 0


 * ------ Example 2  a 4 table join
 *
 *          " where streamA.id = streamB.id " +
            "   and streamB.id = streamC.id";
            "   and streamC.id = streamD.id";

 => join order, ie.
    for stream 0 = {1, 2, 3}
    for stream 1 = {factor [0,2], use 2 for 3}
    for stream 2 = {factor [1,3], use 1 for 0}
    for stream 3 = {2, 1, 0}


 concepts... nested iteration, inner loop

 select * from s1, s2, s3, s4 where s1.id=s2.id and s2.id=s3.id and s3.id=s4.id


 (stream 0)              Nested iteration
    Lookup in stream 1        Lookup in stream 2        Lookup in stream 3

 (stream 1)              Factor
 lookup in stream 0                 Nested iteration
                          Lookup in stream 2        Lookup in stream 3

 (stream 2)              Factor
 lookup in stream 3                 Nested iteration
                          Lookup in stream 1        Lookup in stream 0

 (stream 3)              Nested iteration
    Lookup in stream 2        Lookup in stream 1        Lookup in stream 0

 * ------ Example 4  a 4 table join, orphan table
 *
 *          " where streamA.id = streamB.id " +
            "   and streamB.id = streamC.id"; (no table D join criteria)

 * ------ Example 5  a 3 table join with 2 indexes for stream B
 *
 *          " where streamA.A1 = streamB.B1 " +
            "   and streamB.B2 = streamC.C1"; (no table D join criteria)
 */

/**
 * Builds a query plan for 3 or more streams in a join.
 */
public class NStreamQueryPlanBuilder
{
    /**
     * Build a query plan based on the stream property relationships indicated in queryGraph.
     * @param queryGraph - navigation info between streams
     * @param typesPerStream - event types for each stream
     * @param dependencyGraph - dependencies between historical streams
     * @param historicalStreamIndexLists - index management, populated for the query plan
     * @return query plan
     */
    protected static QueryPlan build(QueryGraph queryGraph,
                                     EventType[] typesPerStream,
                                     HistoricalViewableDesc historicalViewableDesc,
                                     DependencyGraph dependencyGraph,
                                     HistoricalStreamIndexList[] historicalStreamIndexLists,
                                     boolean hasForceNestedIter,
                                     String[][][] indexedStreamsUniqueProps)
    {
        if (log.isDebugEnabled())
        {
            log.debug(".build queryGraph=" + queryGraph);
        }

        int numStreams = queryGraph.getNumStreams();
        QueryPlanIndex[] indexSpecs = QueryPlanIndexBuilder.buildIndexSpec(queryGraph, typesPerStream, indexedStreamsUniqueProps);
        if (log.isDebugEnabled())
        {
            log.debug(".build Index build completed, indexes=" + QueryPlanIndex.print(indexSpecs));
        }

        // any historical streams don't get indexes, the lookup strategy accounts for cached indexes
        if (historicalViewableDesc.isHasHistorical())
        {
            for (int i = 0; i < historicalViewableDesc.getHistorical().length; i++)
            {
                if (historicalViewableDesc.getHistorical()[i])
                {
                    indexSpecs[i] = null;
                }
            }
        }

        QueryPlanNode[] planNodeSpecs = new QueryPlanNode[numStreams];
        int worstDepth = Integer.MAX_VALUE;
        for (int streamNo = 0; streamNo < numStreams; streamNo++)
        {
            // no plan for historical streams that are dependent upon other streams
            if ((historicalViewableDesc.getHistorical()[streamNo]) && (dependencyGraph.hasDependency(streamNo)))
            {
                planNodeSpecs[streamNo] = new QueryPlanNodeNoOp();
                continue;
            }

            BestChainResult bestChainResult = computeBestPath(streamNo, queryGraph, dependencyGraph);
            int[] bestChain = bestChainResult.getChain();
            if (log.isDebugEnabled())
            {
                log.debug(".build For stream " + streamNo + " bestChain=" + Arrays.toString(bestChain));
            }

            if (bestChainResult.depth < worstDepth) {
                worstDepth = bestChainResult.depth;
            }

            planNodeSpecs[streamNo] = createStreamPlan(streamNo, bestChain, queryGraph, indexSpecs, typesPerStream, historicalViewableDesc.getHistorical(), historicalStreamIndexLists);
            if (log.isDebugEnabled())
            {
                log.debug(".build spec=" + planNodeSpecs[streamNo]);
            }
        }

        // We use the merge/nested (outer) join algorithm instead.
        if ((worstDepth < numStreams - 1) && (!hasForceNestedIter)) {
            return null;
        }
        return new QueryPlan(indexSpecs, planNodeSpecs);
    }

    /**
     * Walks the chain of lookups and constructs lookup strategy and plan specification based
     * on the index specifications.
     * @param lookupStream - the stream to construct the query plan for
     * @param bestChain - the chain that the lookup follows to make best use of indexes
     * @param queryGraph - the repository for key properties to indexes
     * @param indexSpecsPerStream - specifications of indexes
     * @param typesPerStream - event types for each stream
     * @param isHistorical - indicator for each stream if it is a historical streams or not
     * @param historicalStreamIndexLists - index management, populated for the query plan
     * @return NestedIterationNode with lookups attached underneath
     */
    protected static QueryPlanNode createStreamPlan(int lookupStream, int[] bestChain, QueryGraph queryGraph,
                                                    QueryPlanIndex[] indexSpecsPerStream, EventType[] typesPerStream,
                                                    boolean[] isHistorical, HistoricalStreamIndexList[] historicalStreamIndexLists)
    {
        NestedIterationNode nestedIterNode = new NestedIterationNode(bestChain);
        int currentLookupStream = lookupStream;

        // Walk through each successive lookup
        for (int i = 0; i < bestChain.length; i++)
        {
            int indexedStream = bestChain[i];

            QueryPlanNode node;
            if (isHistorical[indexedStream])
            {
                if (historicalStreamIndexLists[indexedStream] == null)
                {
                    historicalStreamIndexLists[indexedStream] = new HistoricalStreamIndexList(indexedStream, typesPerStream, queryGraph);
                }
                historicalStreamIndexLists[indexedStream].addIndex(currentLookupStream);
                node = new HistoricalDataPlanNode(indexedStream, lookupStream, currentLookupStream, typesPerStream.length, null);
            }
            else
            {
                TableLookupPlan tableLookupPlan = createLookupPlan(queryGraph, currentLookupStream, indexedStream, indexSpecsPerStream[indexedStream], typesPerStream);
                node = new TableLookupNode(tableLookupPlan);
            }
            nestedIterNode.addChildNode(node);

            currentLookupStream = bestChain[i];
        }

        return nestedIterNode;
    }

    /**
     * Create the table lookup plan for a from-stream to look up in an indexed stream
     * using the columns supplied in the query graph and looking at the actual indexes available
     * and their index number.
     * @param queryGraph - contains properties joining the 2 streams
     * @param currentLookupStream - stream to use key values from
     * @param indexedStream - stream to look up in
     * @param indexSpecs - index specification defining indexes to be created for stream
     * @param typesPerStream - event types for each stream
     * @return plan for performing a lookup in a given table using one of the indexes supplied
     */
    protected static TableLookupPlan createLookupPlan(QueryGraph queryGraph, int currentLookupStream, int indexedStream,
                                               QueryPlanIndex indexSpecs, EventType[] typesPerStream)
    {
        QueryGraphValue queryGraphValue = queryGraph.getGraphValue(currentLookupStream, indexedStream);
        QueryGraphValuePairHashKeyIndex hashKeyProps = queryGraphValue.getHashKeyProps();
        List<QueryGraphValueEntryHashKeyed> hashKeyPropsKeys = hashKeyProps.getKeys();
        String[] hashIndexProps = hashKeyProps.getIndexed();

        QueryGraphValuePairRangeIndex rangeProps = queryGraphValue.getRangeProps();
        List<QueryGraphValueEntryRange> rangePropsKeys = rangeProps.getKeys();
        String[] rangeIndexProps = rangeProps.getIndexed();

        Pair<String, int[]> pairIndexHashRewrite = indexSpecs.getIndexNum(hashIndexProps, rangeIndexProps);
        String indexNum = pairIndexHashRewrite == null ? null : pairIndexHashRewrite.getFirst();

        // handle index redirection towards unique index
        if (pairIndexHashRewrite != null && pairIndexHashRewrite.getSecond() != null) {
            int[] indexes = pairIndexHashRewrite.getSecond();
            String[] newHashIndexProps = new String[indexes.length];
            List<QueryGraphValueEntryHashKeyed> newHashKeys = new ArrayList<QueryGraphValueEntryHashKeyed>();
            for (int i = 0; i < indexes.length; i++) {
                newHashIndexProps[i] = hashIndexProps[indexes[i]];
                newHashKeys.add(hashKeyPropsKeys.get(indexes[i]));
            }
            hashIndexProps = newHashIndexProps;
            hashKeyPropsKeys = newHashKeys;
            rangeIndexProps = new String[0];
            rangePropsKeys = Collections.emptyList();
        }

        // full table scan - no lookups
        if (hashIndexProps.length == 0 && rangeIndexProps.length == 0) {
            // We don't use a keyed index but use the full stream set as the stream does not have any indexes

            // If no such full set index exists yet, add to specs
            if (indexNum == null)
            {
                indexNum = indexSpecs.addIndex(null, null);
            }
            return new FullTableScanLookupPlan(currentLookupStream, indexedStream, indexNum);
        }

        if (indexNum == null)
        {
            throw new IllegalStateException("Failed to query plan as index for " + Arrays.toString(hashIndexProps) + " and " + Arrays.toString(rangeIndexProps) + " in the index specification");
        }

        // straight keyed-index lookup
        if (hashIndexProps.length > 0 && rangeIndexProps.length == 0)
        {
            // Constructed keyed lookup strategy
            List<QueryGraphValueEntryHashKeyed> hashKeys = hashKeyPropsKeys;

            TableLookupPlan tableLookupPlan;
            if (hashKeys.size() == 1) {
                tableLookupPlan = new IndexedTableLookupPlanSingle(currentLookupStream, indexedStream, indexNum, hashKeys.get(0));
            }
            else {
                tableLookupPlan = new IndexedTableLookupPlanMulti(currentLookupStream, indexedStream, indexNum, hashKeys);
            }

            // Determine coercion required
            CoercionDesc coercionTypes = CoercionUtil.getCoercionTypesHash(typesPerStream, currentLookupStream, indexedStream, hashKeys, hashIndexProps);
            if (coercionTypes.isCoerce())
            {
                // check if there already are coercion types for this index
                Class[] existCoercionTypes = indexSpecs.getCoercionTypes(hashIndexProps);
                if (existCoercionTypes != null)
                {
                    for (int i = 0; i < existCoercionTypes.length; i++)
                    {
                        coercionTypes.getCoercionTypes()[i] = JavaClassHelper.getCompareToCoercionType(existCoercionTypes[i], coercionTypes.getCoercionTypes()[i]);
                    }
                }
                indexSpecs.setCoercionTypes(hashIndexProps, coercionTypes.getCoercionTypes());
            }

            return tableLookupPlan;
        }

        // sorted index lookup
        if (hashIndexProps.length == 0 && rangeIndexProps.length == 1) {
            QueryGraphValueEntryRange range = rangePropsKeys.get(0);
            return new SortedTableLookupPlan(currentLookupStream, indexedStream, indexNum, range);
        }
        // composite range and index lookup
        else {
            List<QueryGraphValueEntryHashKeyed> hashKeys = hashKeyPropsKeys;
            return new CompositeTableLookupPlan(currentLookupStream, indexedStream, indexNum, hashKeys, rangePropsKeys);
        }
    }

    /**
     * Compute a best chain or path for lookups to take for the lookup stream passed in and the query
     * property relationships.
     * The method runs through all possible permutations of lookup path {@link NumberSetPermutationEnumeration}
     * until a path is found in which all streams can be accessed via an index.
     * If not such path is found, the method returns the path with the greatest depth, ie. where
     * the first one or more streams are index accesses.
     * If no depth other then zero is found, returns the default nesting order.
     *
     * @param lookupStream - stream to start look up
     * @param queryGraph - navigability between streams
     * @param dependencyGraph - dependencies between historical streams
     * @return chain and chain depth
     */
    protected static BestChainResult computeBestPath(int lookupStream, QueryGraph queryGraph, DependencyGraph dependencyGraph)
    {
        int[] defNestingorder = buildDefaultNestingOrder(queryGraph.getNumStreams(), lookupStream);
        Enumeration<int[]> streamEnum;
        if (defNestingorder.length < 6) {
            streamEnum = new NumberSetPermutationEnumeration(defNestingorder);
        }
        else {
            streamEnum = new NumberSetShiftGroupEnumeration(defNestingorder);
        }
        int[] bestPermutation = null;
        int bestDepth = -1;

        while(streamEnum.hasMoreElements())
        {
            int[] permutation = streamEnum.nextElement();

            // Only if the permutation satisfies all dependencies is the permutation considered
            if (dependencyGraph != null)
            {
                boolean pass = isDependencySatisfied(lookupStream, permutation, dependencyGraph);
                if (!pass)
                {
                    continue;
                }                    
            }

            int permutationDepth = computeNavigableDepth(lookupStream, permutation, queryGraph);

            if (permutationDepth > bestDepth)
            {
                bestPermutation = permutation;
                bestDepth = permutationDepth;
            }

            // Stop when the permutation yielding the full depth (lenght of stream chain) was hit
            if (permutationDepth == queryGraph.getNumStreams() - 1)
            {
                break;
            }
        }

        return new BestChainResult(bestDepth, bestPermutation);
    }

    /**
     * Determine if the proposed permutation of lookups passes dependencies
     * @param lookupStream stream to initiate
     * @param permutation permutation of lookups
     * @param dependencyGraph dependencies
     * @return pass or fail indication
     */
    protected static boolean isDependencySatisfied(int lookupStream, int[] permutation, DependencyGraph dependencyGraph)
    {
        for (Map.Entry<Integer, SortedSet<Integer>> entry : dependencyGraph.getDependencies().entrySet())
        {
            int target = entry.getKey();
            int positionTarget = positionOf(target, lookupStream, permutation);
            if (positionTarget == -1)
            {
                throw new IllegalArgumentException("Target dependency not found in permutation for target " + target + " and permutation " + Arrays.toString(permutation) + " and lookup stream " + lookupStream);
            }

            // check the position of each dependency, it must be higher
            for (int dependency : entry.getValue())
            {
                int positonDep = positionOf(dependency, lookupStream, permutation);
                if (positonDep == -1)
                {
                    throw new IllegalArgumentException("Dependency not found in permutation for dependency " + dependency + " and permutation " + Arrays.toString(permutation)  + " and lookup stream " + lookupStream);
                }

                if (positonDep > positionTarget)
                {
                    return false;
                }
            }
        }
        return true;
    }

    private static int positionOf(int stream, int lookupStream, int[] permutation)
    {
        if (stream == lookupStream)
        {
            return 0;
        }
        for (int i = 0; i < permutation.length; i++)
        {
            if (permutation[i] == stream)
            {
                return i + 1;
            }
        }
        return -1;
    }

    /**
     * Given a chain of streams to look up and indexing information, compute the index within the
     * chain of the first non-index lookup.
     * @param lookupStream - stream to start lookup for
     * @param nextStreams - list of stream numbers next in lookup
     * @param queryGraph - indexing information
     * @return value between 0 and (nextStreams.lenght - 1)
     */
    protected static int computeNavigableDepth(int lookupStream, int[] nextStreams, QueryGraph queryGraph)
    {
        int currentStream = lookupStream;
        int currentDepth = 0;

        for (int i = 0; i < nextStreams.length; i++)
        {
            int nextStream = nextStreams[i];
            boolean navigable = queryGraph.isNavigableAtAll(currentStream, nextStream);
            if (!navigable)
            {
                break;
            }
            currentStream = nextStream;
            currentDepth++;
        }

        return currentDepth;
    }

    /**
     * Returns default nesting order for a given number of streams for a certain stream.
     * Example: numStreams = 5, forStream = 2, result = {0, 1, 3, 4}
     * The resulting array has all streams except the forStream, in ascdending order.
     * @param numStreams - number of streams
     * @param forStream - stream to generate a nesting order for
     * @return int array with all stream numbers starting at 0 to (numStreams - 1) leaving the
     * forStream out
     */
    protected static int[] buildDefaultNestingOrder(int numStreams, int forStream)
    {
        int[] nestingOrder = new int[numStreams - 1];

        int count = 0;
        for (int i = 0; i < numStreams; i++)
        {
            if (i == forStream)
            {
                continue;
            }
            nestingOrder[count++] = i;
        }

        return nestingOrder;
    }

    /**
     * Encapsulates the chain information.
     */
    public static class BestChainResult
    {
        private int depth;
        private int[] chain;

        /**
         * Ctor.
         * @param depth - depth this chain resolves into a indexed lookup
         * @param chain - chain for nested lookup
         */
        public BestChainResult(int depth, int[] chain)
        {
            this.depth = depth;
            this.chain = chain;
        }

        /**
         * Returns depth of lookups via index in chain.
         * @return depth
         */
        public int getDepth()
        {
            return depth;
        }

        /**
         * Returns chain of stream numbers.
         * @return array of stream numbers
         */
        public int[] getChain()
        {
            return chain;
        }

        public String toString()
        {
            return "depth=" + depth + " chain=" + Arrays.toString(chain);
        }
    }

    private static Log log = LogFactory.getLog(NStreamQueryPlanBuilder.class);
}
