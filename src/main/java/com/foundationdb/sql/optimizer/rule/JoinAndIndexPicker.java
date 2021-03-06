/**
 * Copyright (C) 2009-2013 FoundationDB, LLC
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.foundationdb.sql.optimizer.rule;

import com.foundationdb.sql.optimizer.rule.cost.CostEstimator;
import com.foundationdb.sql.optimizer.rule.join_enum.*;
import com.foundationdb.sql.optimizer.rule.join_enum.DPhyp.JoinOperator;

import com.foundationdb.sql.optimizer.plan.*;
import com.foundationdb.sql.optimizer.plan.Sort.OrderByExpression;
import com.foundationdb.sql.optimizer.plan.JoinNode.JoinType;

import com.foundationdb.server.types.texpressions.Comparison;

import com.foundationdb.server.error.AkibanInternalException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/** Pick joins and indexes. 
 * This the the core of actual query optimization.
 */
public class JoinAndIndexPicker extends BaseRule
{
    private static final Logger logger = LoggerFactory.getLogger(JoinAndIndexPicker.class);
    @Override
    protected Logger getLogger() {
        return logger;
    }

    @Override
    public void apply(PlanContext planContext) {
        List<Picker> pickers = new JoinsFinder(planContext).find();
        for (Picker picker : pickers) {
            picker.apply();
        }
    }

    static class Picker {
        Map<SubquerySource,Picker> subpickers;
        PlanContext planContext;
        SchemaRulesContext rulesContext;
        Joinable joinable;
        BaseQuery query;
        QueryIndexGoal queryGoal;
        ConditionList originalSubqueryWhereConditions;

        public Picker(Joinable joinable, BaseQuery query,
                      PlanContext planContext,
                      Map<SubquerySource,Picker> subpickers) {
            this.subpickers = subpickers;
            this.planContext = planContext;
            this.rulesContext = (SchemaRulesContext)planContext.getRulesContext();
            this.joinable = joinable;
            this.query = query;
        }

        public CostEstimator getCostEstimator() {
            return rulesContext.getCostEstimator();
        }
        
        public PlanContext getPlanContext() { 
            return planContext;
        }
        
        public void apply() {
            queryGoal = determineQueryIndexGoal(joinable);
            if (joinable instanceof TableGroupJoinTree) {
                // Single group.
                pickIndex((TableGroupJoinTree)joinable);
            }
            else if (joinable instanceof JoinNode) {
                // General joins.
                pickJoinsAndIndexes((JoinNode)joinable);
            }
            else if (joinable instanceof SubquerySource) {
                // Single subquery // view. Just do its insides.
                subpicker((SubquerySource)joinable).apply();
            }
            // TODO: Any other degenerate cases?
        }

        protected QueryIndexGoal determineQueryIndexGoal(PlanNode input) {
            ConditionList whereConditions = null;
            Sort ordering = null;
            AggregateSource grouping = null;
            Project projectDistinct = null;
            Limit limit = null;
            input = input.getOutput();
            if (input instanceof Select) {
                whereConditions = ((Select)input).getConditions();
            }
            input = input.getOutput();
            if (input instanceof Sort) {
                ordering = (Sort)input;
                input = input.getOutput();
                if (input instanceof Project)
                    input = input.getOutput();
            }
            else if (input instanceof AggregateSource) {
                grouping = (AggregateSource)input;
                if (!grouping.hasGroupBy())
                    grouping = null;
                input = input.getOutput();
                if (input instanceof Select)
                    input = input.getOutput();
                if (input instanceof Sort) {
                    // Needs to be possible to satisfy both.
                    ordering = (Sort)input;
                    if (grouping != null) {
                        List<ExpressionNode> groupBy = grouping.getGroupBy();
                        for (OrderByExpression orderBy : ordering.getOrderBy()) {
                            ExpressionNode orderByExpr = orderBy.getExpression();
                            if (!((orderByExpr.isColumn() &&
                                   (((ColumnExpression)orderByExpr).getTable() == grouping)) ||
                                  groupBy.contains(orderByExpr))) {
                                ordering = null;
                                break;
                            }
                        }
                    }
                    if (ordering != null) // No limit if sort lost.
                        input = input.getOutput();
                }
                if (input instanceof Project)
                    input = input.getOutput();
            }
            else if (input instanceof Project) {
                Project project = (Project)input;
                input = project.getOutput();
                if (input instanceof Distinct) {
                    projectDistinct = project;
                    input = input.getOutput();
                }
                else if (input instanceof Sort) {
                    ordering = (Sort)input;
                    input = input.getOutput();
                    if (input instanceof Distinct)
                        input = input.getOutput(); // Not projectDistinct (already marked as explicitly sorted).
                }
            }
            if (input instanceof Limit)
                limit = (Limit)input;
            return new QueryIndexGoal(query, rulesContext, whereConditions, 
                                      grouping, ordering, projectDistinct, limit);
        }

        // Only a single group of tables. Don't need to run general
        // join algorithm and can shortcut some of the setup for this
        // group.
        protected void pickIndex(TableGroupJoinTree tables) {
            GroupIndexGoal groupGoal = new GroupIndexGoal(queryGoal, tables, planContext);
            List<JoinOperator> empty = Collections.emptyList(); // No more joins / bound tables.
            groupGoal.updateRequiredColumns(empty, empty);
            BaseScan scan = groupGoal.pickBestScan();
            groupGoal.install(scan, null, true, false);
            query.setCostEstimate(scan.getCostEstimate());
        }

        // General joins: run enumerator.
        protected void pickJoinsAndIndexes(JoinNode joins) {
            Plan rootPlan = new JoinEnumerator(this).run(joins, queryGoal.getWhereConditions()).bestPlan(Collections.<JoinOperator>emptyList());
            installPlan(rootPlan, false);
        }

        // Put the chosen plan in place.
        public void installPlan(Plan rootPlan, boolean copy) {
            joinable.getOutput().replaceInput(joinable, 
                                              moveInSemiJoins(rootPlan.install(copy)));
            query.setCostEstimate(rootPlan.costEstimate);
        }

        // If any semi-joins to VALUES are left over at the top, they
        // can be put into the Select and then possibly moved earlier.
        protected Joinable moveInSemiJoins(Joinable joined) {
            if (queryGoal.getWhereConditions() != null) {
                while (joined instanceof JoinNode) {
                    JoinNode join = (JoinNode)joined;
                    if (join.getJoinType() != JoinType.SEMI) 
                        break;
                    Joinable right = join.getRight();
                    if (!(right instanceof ExpressionsSource))
                        break;
                    // Cf. GroupIndexGoal.semiJoinToInList.
                    ExpressionsSource values = (ExpressionsSource)right;
                    ComparisonCondition ccond = null;
                    boolean found = false;
                    if ((join.getJoinConditions() != null) &&
                        (join.getJoinConditions().size() == 1)) {
                        ConditionExpression joinCondition = join.getJoinConditions().get(0);
                        if (joinCondition instanceof ComparisonCondition) {
                            ccond = (ComparisonCondition)joinCondition;
                            if ((ccond.getOperation() == Comparison.EQ) &&
                                (ccond.getRight() instanceof ColumnExpression)) {
                                ColumnExpression rcol = (ColumnExpression)ccond.getRight();
                                if ((rcol.getTable() == values) &&
                                    (rcol.getPosition() == 0)) {
                                    found = true;
                                }
                            }
                        }
                    }
                    if (!found) break;
                    // Replace semi-join with In Select condition.
                    queryGoal.getWhereConditions()
                        .add(GroupIndexGoal.semiJoinToInList(values, ccond, rulesContext));
                    joined = join.getLeft();
                }
            }
            return joined;
        }

        // Get the handler for the given subquery so that it can be done in context.
        public Picker subpicker(SubquerySource subquery) {
            Picker subpicker = subpickers.get(subquery);
            assert (subpicker != null);
            return subpicker;
        }

        // Subquery but as part of a larger plan tree. Return best
        // plan to be installed with it.
        public Plan subqueryPlan(Set<ColumnSource> subqueryBoundTables,
                                 Collection<JoinOperator> subqueryJoins,
                                 Collection<JoinOperator> subqueryOutsideJoins) {
            if (queryGoal == null) {
                queryGoal = determineQueryIndexGoal(joinable);
                if (queryGoal.getWhereConditions() != null)
                    originalSubqueryWhereConditions = new ConditionList(queryGoal.getWhereConditions());
            }
            if (joinable instanceof TableGroupJoinTree) {
                TableGroupJoinTree tables = (TableGroupJoinTree)joinable;
                GroupIndexGoal groupGoal = new GroupIndexGoal(queryGoal, tables, planContext);
                // In this block because we were not a JoinNode, query has no joins itself
                List<JoinOperator> queryJoins = Collections.emptyList();
                List<ConditionList> conditionSources = groupGoal.updateContext(subqueryBoundTables, queryJoins, subqueryJoins, subqueryOutsideJoins, true, null);
                BaseScan scan = groupGoal.pickBestScan();
                CostEstimate costEstimate = scan.getCostEstimate();
                return new GroupPlan(groupGoal, JoinableBitSet.of(0), scan, costEstimate, conditionSources, true, null);
            }
            if (joinable instanceof JoinNode) {
                if ((originalSubqueryWhereConditions != null) &&
                    (originalSubqueryWhereConditions.size() != queryGoal.getWhereConditions().size())) {
                    // Restore possible join conditions for new enumerator.
                    queryGoal.getWhereConditions().clear();
                    queryGoal.getWhereConditions().addAll(originalSubqueryWhereConditions);
                }
                return new JoinEnumerator(this, subqueryBoundTables, subqueryJoins, subqueryOutsideJoins).run((JoinNode)joinable, queryGoal.getWhereConditions()).bestPlan(Collections.<JoinOperator>emptyList());
            }
            if (joinable instanceof SubquerySource) {
                return subpicker((SubquerySource)joinable).subqueryPlan(subqueryBoundTables, subqueryJoins, subqueryOutsideJoins);
            }
            throw new AkibanInternalException("Unknown join element: " + joinable);
        }

    }

    static abstract class Plan implements Comparable<Plan> {
        CostEstimate costEstimate;

        protected Plan(CostEstimate costEstimate) {
            this.costEstimate = costEstimate;
        }

        public int compareTo(Plan other) {
            return costEstimate.compareTo(other.costEstimate);
        }

        public abstract Joinable install(boolean copy);

        public void addDistinct() {
            throw new UnsupportedOperationException();
        }

        public boolean semiJoinEquivalent() {
            return false;
        }

        public boolean containsColumn(ColumnExpression column) {
            return false;
        }

        public double joinSelectivity() {
            return 1.0;
        }

        public void redoCostWithLimit(long limit) {
        }
    }

    static abstract class PlanClass {
        JoinEnumerator enumerator;        
        long bitset;

        protected PlanClass(JoinEnumerator enumerator, long bitset) {
            this.enumerator = enumerator;
            this.bitset = bitset;
        }

        public abstract Plan bestPlan(Collection<JoinOperator> outsideJoins);

        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            return bestPlan(outsideJoins); // By default, side doesn't matter.
        }
    }
    
    static class GroupPlan extends Plan {
        GroupIndexGoal groupGoal;
        long outerTables;
        BaseScan scan;
        List<ConditionList> conditionSources;
        boolean sortAllowed;
        ConditionList extraConditions;

        public GroupPlan(GroupIndexGoal groupGoal,
                         long outerTables, BaseScan scan, 
                         CostEstimate costEstimate,
                         List<ConditionList> conditionSources,
                         boolean sortAllowed,
                         ConditionList extraConditions) {
            super(costEstimate);
            this.groupGoal = groupGoal;
            this.outerTables = outerTables;
            this.scan = scan;
            this.conditionSources = conditionSources;
            this.sortAllowed = sortAllowed;
            this.extraConditions = extraConditions;
        }

        @Override
        public String toString() {
            return scan.toString();
        }

        @Override
        public Joinable install(boolean copy) {
            if (extraConditions != null) {
                // Move to WHERE clause or join condition so that any
                // that survive indexing are preserved.
                assert ((conditionSources.size() > 1) &&
                        (conditionSources.indexOf(extraConditions) > 0));
                conditionSources.get(0).addAll(extraConditions);
            }
            return groupGoal.install(scan, conditionSources, sortAllowed, copy);
        }

        public boolean orderedForDistinct(Distinct distinct) {
            if (!((distinct.getInput() instanceof Project) &&
                  (scan instanceof IndexScan)))
                return false;
            return groupGoal.orderedForDistinct((Project)distinct.getInput(),
                                                (IndexScan)scan);
        }

        @Override
        public boolean semiJoinEquivalent() {
            return groupGoal.semiJoinEquivalent(scan);
        }

        @Override
        public boolean containsColumn(ColumnExpression column) {
            ColumnSource table = column.getTable();
            if (!(table instanceof TableSource)) return false;
            return groupGoal.getTables().containsTable((TableSource)table);
        }

        @Override
        public double joinSelectivity() {
            if (scan instanceof IndexScan)
                return groupGoal.estimateSelectivity((IndexScan)scan);
            else
                return super.joinSelectivity();
        }

        @Override
        public void redoCostWithLimit(long limit) {
            if (scan instanceof IndexScan) {
                costEstimate = groupGoal.estimateCost((IndexScan)scan, limit);
            }
        }
    }

    static class GroupPlanClass extends PlanClass {
        GroupIndexGoal groupGoal;
        Collection<GroupPlan> bestPlans = new ArrayList<>();

        public GroupPlanClass(JoinEnumerator enumerator, long bitset, 
                              GroupIndexGoal groupGoal) {
            super(enumerator, bitset);
            this.groupGoal = groupGoal;
        }

        protected GroupPlanClass(GroupPlanClass other) {
            super(other.enumerator, other.bitset);
            this.groupGoal = other.groupGoal;
        }

        @Override
        public Plan bestPlan(Collection<JoinOperator> outsideJoins) {
            return bestPlan(JoinableBitSet.empty(), Collections.<JoinOperator>emptyList(), outsideJoins);
        }

        @Override
        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            return bestPlan(outerPlan.bitset, joins, outsideJoins);
        }

        protected ConditionList getExtraConditions() {
            return null;
        }

        protected GroupPlan bestPlan(long outerTables, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            for (GroupPlan groupPlan : bestPlans) {
                if (groupPlan.outerTables == outerTables) {
                    return groupPlan;
                }
            }
            boolean sortAllowed = joins.isEmpty();
            List<ConditionList> conditionSources = groupGoal.updateContext(enumerator.boundTables(outerTables), joins, joins, outsideJoins, sortAllowed, getExtraConditions());
            BaseScan scan = groupGoal.pickBestScan();
            CostEstimate costEstimate = scan.getCostEstimate();
            GroupPlan groupPlan = new GroupPlan(groupGoal, outerTables, scan, costEstimate, conditionSources, sortAllowed, getExtraConditions());
            bestPlans.add(groupPlan);
            return groupPlan;
        }
    }

    static class GroupWithInPlanClass extends GroupPlanClass {
        ConditionList inConditions;

        public GroupWithInPlanClass(GroupPlanClass left,
                                    ValuesPlanClass right,
                                    Collection<JoinOperator> joins) {
            super(left);
            InListCondition incond = left.groupGoal.semiJoinToInList(right.plan.values, joins);
            if (incond != null) {
                inConditions = new ConditionList(1);
                inConditions.add(incond);
            }
        }

        public GroupWithInPlanClass(GroupWithInPlanClass left,
                                    ValuesPlanClass right,
                                    Collection<JoinOperator> joins) {
            super(left);
            InListCondition incond = left.groupGoal.semiJoinToInList(right.plan.values, joins);
            if (incond != null) {
                inConditions = new ConditionList(left.inConditions);
                inConditions.add(incond);
            }
        }

        @Override
        protected ConditionList getExtraConditions() {
            return inConditions;
        }

    }

    static class SubqueryPlan extends Plan {
        SubquerySource subquery;
        Picker picker;
        long outerTables;
        Plan rootPlan;

        public SubqueryPlan(SubquerySource subquery, Picker picker,
                            long outerTables, Plan rootPlan, 
                            CostEstimate costEstimate) {
            super(costEstimate);
            this.subquery = subquery;
            this.picker = picker;
            this.outerTables = outerTables;
            this.rootPlan = rootPlan;
        }

        @Override
        public String toString() {
            return rootPlan.toString();
        }


        @Override
        public Joinable install(boolean copy) {
            picker.installPlan(rootPlan, copy);
            return subquery;
        }        

        @Override
        public void addDistinct() {
            Subquery output = subquery.getSubquery();
            PlanNode input = output.getInput();
            Distinct distinct = new Distinct(input);
            output.replaceInput(input, distinct);
            if ((rootPlan instanceof GroupPlan) &&
                ((GroupPlan)rootPlan).orderedForDistinct(distinct)) {
                distinct.setImplementation(Distinct.Implementation.PRESORTED);
            }
        }
    }

    static class SubqueryPlanClass extends PlanClass {
        SubquerySource subquery;
        Picker picker;
        Collection<SubqueryPlan> bestPlans = new ArrayList<>();

        public SubqueryPlanClass(JoinEnumerator enumerator, long bitset, 
                                 SubquerySource subquery, Picker picker) {
            super(enumerator, bitset);
            this.subquery = subquery;
            this.picker = picker;
        }

        @Override
        public Plan bestPlan(Collection<JoinOperator> outsideJoins) {
            return bestPlan(JoinableBitSet.empty(), Collections.<JoinOperator>emptyList(), outsideJoins);
        }

        @Override
        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            return bestPlan(outerPlan.bitset, joins, outsideJoins);
        }

        protected SubqueryPlan bestPlan(long outerTables, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            for (SubqueryPlan subqueryPlan : bestPlans) {
                if (subqueryPlan.outerTables == outerTables) {
                    return subqueryPlan;
                }
            }
            Plan rootPlan = picker.subqueryPlan(enumerator.boundTables(outerTables), joins, outsideJoins);
            CostEstimate costEstimate = rootPlan.costEstimate;
            SubqueryPlan subqueryPlan = new SubqueryPlan(subquery, picker,
                                                         outerTables, rootPlan, 
                                                         costEstimate);
            bestPlans.add(subqueryPlan);
            return subqueryPlan;
        }
    }

    static class ValuesPlan extends Plan {
        ExpressionsSource values;
        
        public ValuesPlan(ExpressionsSource values, CostEstimate costEstimate) {
            super(costEstimate);
            this.values = values;
        }

        @Override
        public String toString() {
            return values.getName();
        }


        @Override
        public Joinable install(boolean copy) {
            return values;
        }

        @Override
        public void addDistinct() {
            values.setDistinctState(ExpressionsSource.DistinctState.NEED_DISTINCT);
        }
    }

    static class ValuesPlanClass extends PlanClass {
        ValuesPlan plan, nestedPlan;

        public ValuesPlanClass(JoinEnumerator enumerator, long bitset, 
                               ExpressionsSource values, Picker picker) {
            super(enumerator, bitset);
            CostEstimator costEstimator = picker.getCostEstimator();
            this.plan = new ValuesPlan(values, costEstimator.costValues(values, false));
            // Nested also needs to check the join condition with Select.
            this.nestedPlan = new ValuesPlan(values, costEstimator.costValues(values, true));
        }

        @Override
        public Plan bestPlan(Collection<JoinOperator> outsideJoins) {
            return plan;
        }

        @Override
        public Plan bestNestedPlan(PlanClass outerPlan, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            return nestedPlan;
        }
    }

    static class JoinPlan extends Plan {
        Plan left, right;
        JoinType joinType;
        JoinNode.Implementation joinImplementation;
        Collection<JoinOperator> joins;
        boolean needDistinct;
        
        public JoinPlan(Plan left, Plan right, 
                        JoinType joinType, JoinNode.Implementation joinImplementation,
                        Collection<JoinOperator> joins, CostEstimate costEstimate) {
            super(costEstimate);
            this.left = left;
            this.right = right;
            switch (joinType) {
            case SEMI_INNER_ALREADY_DISTINCT:
            case SEMI_INNER_IF_DISTINCT:
                this.joinType = JoinType.SEMI;
                break;
            case INNER_NEED_DISTINCT:
                this.joinType = JoinType.INNER;
                needDistinct = true;
                break;
            default:
                this.joinType = joinType;
            }
            this.joinImplementation = joinImplementation;
            this.joins = joins;
        }

        @Override
        public String toString() {
            return "(" + left + ") " +
                joinType + "/" + joinImplementation +
                " (" + right + ")";
        }

        @Override
        public Joinable install(boolean copy) {
            if (needDistinct)
                left.addDistinct();
            Joinable leftJoinable = left.install(copy);
            Joinable rightJoinable = right.install(copy);
            ConditionList joinConditions = mergeJoinConditions(joins);
            JoinNode join = new JoinNode(leftJoinable, rightJoinable, joinType);
            join.setJoinConditions(joinConditions);
            join.setImplementation(joinImplementation);
            if (joinType == JoinType.SEMI)
                InConditionReverser.cleanUpSemiJoin(join, rightJoinable);
            return join;
        }

        protected ConditionList mergeJoinConditions(Collection<JoinOperator> joins) {
            ConditionList joinConditions = null;
            boolean newJoinConditions = false;
            for (JoinOperator joinOp : joins) {
                if ((joinOp.getJoinConditions() != null) &&
                    !joinOp.getJoinConditions().isEmpty()) {
                    if (joinConditions == null) {
                        joinConditions = joinOp.getJoinConditions();
                    }
                    else { 
                        if (!newJoinConditions) {
                            joinConditions = new ConditionList(joinConditions);
                            newJoinConditions = true;
                        }
                        joinConditions.addAll(joinOp.getJoinConditions());
                    }
                }
            }
            return joinConditions;
        }

        public void redoCostWithLimit(long limit) {
            left.redoCostWithLimit(limit);
            costEstimate = left.costEstimate.nest(right.costEstimate);
        }
    }

    static class HashJoinPlan extends JoinPlan {
        Plan loader;
        BaseHashTable hashTable;
        List<ExpressionNode> hashColumns, matchColumns;
        
        public HashJoinPlan(Plan loader, Plan input, Plan check,
                            JoinType joinType, JoinNode.Implementation joinImplementation,
                            Collection<JoinOperator> joins, CostEstimate costEstimate,
                            BaseHashTable hashTable, List<ExpressionNode> hashColumns, List<ExpressionNode> matchColumns) {
            super(input, check, joinType, joinImplementation, joins, costEstimate);
            this.loader = loader;
            this.hashTable = hashTable;
            this.hashColumns = hashColumns;
            this.matchColumns = matchColumns;
        }

        @Override
        public Joinable install(boolean copy) {
            if (needDistinct)
                left.addDistinct();
            Joinable loaderJoinable = loader.install(true);
            Joinable inputJoinable = left.install(copy);
            Joinable checkJoinable = right.install(copy);
            ConditionList joinConditions = mergeJoinConditions(joins);
            HashJoinNode join = new HashJoinNode(loaderJoinable, inputJoinable, checkJoinable, joinType, hashTable, hashColumns, matchColumns);
            join.setJoinConditions(joinConditions);
            join.setImplementation(joinImplementation);
            if (joinType == JoinType.SEMI)
                InConditionReverser.cleanUpSemiJoin(join, checkJoinable);
            return join;
        }
    }

    static class JoinPlanClass extends PlanClass {
        Plan bestPlan;      // TODO: Later have separate sorted, etc.
        GroupWithInPlanClass asGroupWithIn; // If semi-joined to one or more VALUES.

        public JoinPlanClass(JoinEnumerator enumerator, long bitset) {
            super(enumerator, bitset);
        }

        @Override
        public Plan bestPlan(Collection<JoinOperator> outsideJoins) {
            return bestPlan;
        }

        public void consider(Plan plan) {
            if (bestPlan == null) {
                logger.debug("Selecting {}, {}", plan, plan.costEstimate);
                bestPlan = plan;
            }
            else if (bestPlan.compareTo(plan) > 0) {
                logger.debug("Preferring {}, {}", plan, plan.costEstimate);
                bestPlan = plan;
            }
            else {
                logger.debug("Rejecting {}, {}", plan, plan.costEstimate);
            }
        }
    }

    static class JoinEnumerator extends DPhyp<PlanClass> {
        private Picker picker;
        private Set<ColumnSource> subqueryBoundTables;
        private Collection<JoinOperator> subqueryJoins, subqueryOutsideJoins;

        public JoinEnumerator(Picker picker) {
            this.picker = picker;
        }

        public JoinEnumerator(Picker picker, Set<ColumnSource> subqueryBoundTables, Collection<JoinOperator> subqueryJoins, Collection<JoinOperator> subqueryOutsideJoins) {
            this.picker = picker;
            this.subqueryBoundTables = subqueryBoundTables;
            this.subqueryJoins = subqueryJoins;
            this.subqueryOutsideJoins = subqueryOutsideJoins;
        }

        @Override
        public PlanClass evaluateTable(long s, Joinable joinable) {
            // Seed with the right plan class to hold state / alternatives.
            if (joinable instanceof TableGroupJoinTree) {
                GroupIndexGoal groupGoal = new GroupIndexGoal(picker.queryGoal, 
                                                              (TableGroupJoinTree)joinable, picker.getPlanContext());
                return new GroupPlanClass(this, s, groupGoal);
            }
            if (joinable instanceof SubquerySource) {
                SubquerySource subquery = (SubquerySource)joinable;
                Picker subpicker = picker.subpicker(subquery);
                return new SubqueryPlanClass(this, s, subquery, subpicker);
            }
            if (joinable instanceof ExpressionsSource) {
                return new ValuesPlanClass(this, s, (ExpressionsSource)joinable, picker);
            }
            throw new AkibanInternalException("Unknown join element: " + joinable);
        }

        @Override
        public PlanClass evaluateJoin(long leftBitset, PlanClass left, 
                                      long rightBitset, PlanClass right, 
                                      long bitset, PlanClass existing,
                                      JoinType joinType, Collection<JoinOperator> joins, Collection<JoinOperator> outsideJoins) {
            JoinPlanClass planClass = (JoinPlanClass)existing;
            if (planClass == null)
                planClass = new JoinPlanClass(this, bitset);
            if ((joinType.isSemi() && (right instanceof ValuesPlanClass))) {
                // Semi-join a VALUES on the inside by turning it into
                // predicate, which can be better optimized.
                assert (planClass.asGroupWithIn == null);
                GroupWithInPlanClass asGroupWithIn = null;
                if (left instanceof GroupPlanClass) {
                    asGroupWithIn = new GroupWithInPlanClass((GroupPlanClass)left,
                                                             (ValuesPlanClass)right,
                                                             joins);
                }
                else if (left instanceof JoinPlanClass) {
                    JoinPlanClass leftJoinPlanClass = (JoinPlanClass)left;
                    if (leftJoinPlanClass.asGroupWithIn != null) {
                        asGroupWithIn = new GroupWithInPlanClass(leftJoinPlanClass.asGroupWithIn,
                                                                 (ValuesPlanClass)right,
                                                                 joins);
                    }
                }
                if ((asGroupWithIn != null) &&
                    (asGroupWithIn.getExtraConditions() != null)) {
                    Plan withInPlan = asGroupWithIn.bestPlan(outsideJoins);
                    if (withInPlan != null) {
                        planClass.asGroupWithIn = asGroupWithIn;
                        planClass.consider(withInPlan);
                    }
                    return planClass;
                }
            }
            joins = new ArrayList<>(joins);
            Collection<JoinOperator> condJoins = joins; // Joins with conditions for indexing.
            if (subqueryJoins != null) {
                // "Push down" joins into the subquery. Since these
                // are joins to the dervived table, they still need to
                // be recognized to match an indexable column.
                condJoins = new ArrayList<>(joins);
                condJoins.addAll(subqueryJoins);
            }
            if (subqueryOutsideJoins != null) {
                outsideJoins.addAll(subqueryOutsideJoins);
            }
            outsideJoins.addAll(joins); // Total set for outer; inner must subtract.
            // TODO: Divvy up sorting. Consider group joins. Consider merge joins.
            Plan leftPlan = left.bestPlan(outsideJoins);
            Plan rightPlan = right.bestNestedPlan(left, condJoins, outsideJoins);
            CostEstimate costEstimate = leftPlan.costEstimate.nest(rightPlan.costEstimate);
            JoinPlan joinPlan = new JoinPlan(leftPlan, rightPlan,
                                             joinType, JoinNode.Implementation.NESTED_LOOPS,
                                             joins, costEstimate);
            if (joinType.isSemi() || rightPlan.semiJoinEquivalent()) {
                Plan loaderPlan = right.bestPlan(outsideJoins);
                JoinPlan hashPlan = buildBloomFilterSemiJoin(loaderPlan, joinPlan);
                if (hashPlan != null)
                    planClass.consider(hashPlan);
            }
            planClass.consider(joinPlan);
            return planClass;
        }

        /** Get the tables that correspond to the given bitset, plus
         * any that are bound outside the subquery, either
         * syntactically or via joins to it.
         */
        public Set<ColumnSource> boundTables(long tables) {
            if (JoinableBitSet.isEmpty(tables) &&
                (subqueryBoundTables == null))
                return picker.queryGoal.getQuery().getOuterTables();
            Set<ColumnSource> boundTables = new HashSet<>();
            boundTables.addAll(picker.queryGoal.getQuery().getOuterTables());
            if (subqueryBoundTables != null)
                boundTables.addAll(subqueryBoundTables);
            if (!JoinableBitSet.isEmpty(tables)) {
                for (int i = 0; i < 64; i++) {
                    if (JoinableBitSet.overlaps(tables, JoinableBitSet.of(i))) {
                        Joinable table = getTable(i);
                        if (table instanceof TableGroupJoinTree) {
                            for (TableGroupJoinTree.TableGroupJoinNode gtable : (TableGroupJoinTree)table) {
                                boundTables.add(gtable.getTable());
                            }
                        }
                        else {
                            boundTables.add((ColumnSource)table);
                        }
                    }
                }
            }
            return boundTables;
        }

        double BLOOM_FILTER_MAX_SELECTIVITY_DEFAULT = 0.05;

        public JoinPlan buildBloomFilterSemiJoin(Plan loaderPlan, JoinPlan joinPlan) {
            Plan inputPlan = joinPlan.left;
            Plan checkPlan = joinPlan.right;
            Collection<JoinOperator> joins = joinPlan.joins;
            if (checkPlan.costEstimate.getRowCount() > 1)
                return null;    // Join not selective.
            double maxSelectivity;
            String prop = picker.rulesContext.getProperty("bloomFilterMaxSelectivity");
            if (prop != null)
                maxSelectivity = Double.valueOf(prop);
            else
                maxSelectivity = BLOOM_FILTER_MAX_SELECTIVITY_DEFAULT;
            if (maxSelectivity <= 0.0) return null; // Feature turned off.
            List<ExpressionNode> hashColumns = new ArrayList<>();
            List<ExpressionNode> matchColumns = new ArrayList<>();
            for (JoinOperator join : joins) {
                if (join.getJoinConditions() != null) {
                    for (ConditionExpression cond : join.getJoinConditions()) {
                        if (!(cond instanceof ComparisonCondition)) return null;
                        ComparisonCondition ccond = (ComparisonCondition)cond;
                        if (ccond.getOperation() != Comparison.EQ) return null;
                        ExpressionNode left = ccond.getLeft();
                        ExpressionNode right = ccond.getRight();
                        // TODO: Could allow somewhat more
                        // complicated, provided still just use one
                        // side's tables.
                        if (!((left instanceof ColumnExpression) &&
                              (right instanceof ColumnExpression)))
                            return null;
                        if (inputPlan.containsColumn((ColumnExpression)left) && 
                            checkPlan.containsColumn((ColumnExpression)right)) {
                            matchColumns.add(left);
                            hashColumns.add(right);
                        }
                        else if (inputPlan.containsColumn((ColumnExpression)right) && 
                                 checkPlan.containsColumn((ColumnExpression)left)) {
                            matchColumns.add(right);
                            hashColumns.add(left);
                        }
                        else {
                            return null;
                        }
                    }
                }
            }
            double selectivity = checkPlan.joinSelectivity();
            if (selectivity > maxSelectivity)
                return null;
            long limit = picker.queryGoal.getLimit();
            if (joinPlan.costEstimate.getRowCount() == limit) {
                // If there was a limit, it was applied too liberally
                // for the very non-selective join.
                // TODO: This should have always been the cost of the
                // join plan, but that would be too disruptive, so
                // only do it when need to make the comparison with
                // the Bloom filter accurate.
                limit = Math.round(limit / selectivity);
                joinPlan.redoCostWithLimit(limit);
            }
            BloomFilter bloomFilter = new BloomFilter(loaderPlan.costEstimate.getRowCount(), 1);
            CostEstimate costEstimate = picker.getCostEstimator()
                .costBloomFilter(loaderPlan.costEstimate, inputPlan.costEstimate, checkPlan.costEstimate, selectivity);
            return new HashJoinPlan(loaderPlan, inputPlan, checkPlan,
                                    JoinType.SEMI, JoinNode.Implementation.BLOOM_FILTER,
                                    joins, costEstimate, bloomFilter, hashColumns, matchColumns);
        }
    }
    
    // Find top-level joins and note what query they come from; 
    // Top-level queries and those used in expressions are returned directly.
    // Derived tables are deferred, since they need to be planned in
    // the context of various join orders to allow for join predicated
    // to be pushed "inside." So they are stored in a Map accessible
    // to other Pickers.
    static class JoinsFinder extends SubqueryBoundTablesTracker {
        List<Picker> result;
        Map<SubquerySource,Picker> subpickers;

        public JoinsFinder(PlanContext planContext) {
            super(planContext);
        }

        public List<Picker> find() {
            result = new ArrayList<>();
            subpickers = new HashMap<>();
            run();
            result.removeAll(subpickers.values()); // Do these in context.
            return result;
        }

        @Override
        public boolean visit(PlanNode n) {
            super.visit(n);
            if ((n instanceof Joinable) && !(n instanceof TableSource)) {
                Joinable j = (Joinable)n;
                while (j.getOutput() instanceof Joinable)
                    j = (Joinable)j.getOutput();
                BaseQuery query = currentQuery();
                SubquerySource subquerySource = null;
                if (query.getOutput() instanceof SubquerySource)
                    subquerySource = (SubquerySource)query.getOutput();
                for (Picker picker : result) {
                    if (picker.joinable == j)
                        // Already have another set of joins to same root join.
                        return true;
                }
                Picker picker = new Picker(j, query, planContext, subpickers);
                result.add(picker);
                if (subquerySource != null)
                    subpickers.put(subquerySource, picker);
            }
            return true;
        }
    }
}
