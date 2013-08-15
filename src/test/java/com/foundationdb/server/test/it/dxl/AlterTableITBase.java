/**
 * Copyright (C) 2009-2013 Akiban Technologies, Inc.
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

package com.foundationdb.server.test.it.dxl;

import com.foundationdb.ais.AISCloner;
import com.foundationdb.ais.model.AISTableNameChanger;
import com.foundationdb.ais.model.AkibanInformationSchema;
import com.foundationdb.ais.model.Column;
import com.foundationdb.ais.model.IndexColumn;
import com.foundationdb.ais.model.TableIndex;
import com.foundationdb.ais.model.TableName;
import com.foundationdb.ais.model.UserTable;
import com.foundationdb.ais.util.TableChange;
import com.foundationdb.qp.operator.QueryContext;
import com.foundationdb.qp.row.RowBase;
import com.foundationdb.qp.rowtype.RowType;
import com.foundationdb.server.api.dml.scan.NewRow;
import com.foundationdb.server.service.tree.TreeService;
import com.foundationdb.server.test.it.ITBase;
import com.foundationdb.server.test.it.qp.TestRow;
import org.junit.After;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.foundationdb.ais.util.TableChangeValidator.ChangeLevel;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

public class AlterTableITBase extends ITBase {
    private final static String EXPECTED_VOLUME_NAME = "persistit_data";

    protected static final String SCHEMA = "test";
    protected static final String X_TABLE = "x";
    protected static final String C_TABLE = "c";
    protected static final String O_TABLE = "o";
    protected static final String I_TABLE = "i";
    protected static final TableName X_NAME = new TableName(SCHEMA, X_TABLE);
    protected static final TableName C_NAME = new TableName(SCHEMA, C_TABLE);
    protected static final TableName O_NAME = new TableName(SCHEMA, O_TABLE);
    protected static final TableName I_NAME = new TableName(SCHEMA, I_TABLE);
    protected static final List<TableChange> NO_CHANGES = Collections.emptyList();

    protected Map<Integer,List<String>> checkedIndexes = new HashMap<>();

    // Workaround for bug1052594 (Persistit brings trees back to life, this deletes data dir)
    @Override
    protected Map<String, String> startupConfigProperties() {
        return uniqueStartupConfigProperties(AlterTableITBase.class);
    }

    @After
    public void lookForCheckedIndexes() {
        for(Map.Entry<Integer, List<String>> entry : checkedIndexes.entrySet()) {
            List<String> value = entry.getValue();
            expectIndexes(entry.getKey(), value.toArray(new String[value.size()]));
        }
        checkedIndexes.clear();
    }

    // Added after bug1047977
    @After
    public void lookForDanglingTrees() throws Exception {
        // Collect all trees Persistit currently has
        Set<String> storageTrees = new TreeSet<>();
        storageTrees.addAll(Arrays.asList(treeService().getDb().getVolume(EXPECTED_VOLUME_NAME).getTreeNames()));

        // Collect all trees in AIS
        Set<String> knownTrees = serviceManager().getSchemaManager().getTreeNames();
        knownTrees.add(TreeService.SCHEMA_TREE_NAME); // Used by SchemaManager

        // Subtract knownTrees from storage trees instead of requiring exact. There may be allocated trees that
        // weren't materialized (yet), for example.
        Set<String> difference = new TreeSet<>(storageTrees);
        difference.removeAll(knownTrees);

        assertEquals("Found orphaned trees", "[]", difference.toString());
    }

    protected void checkIndexesInstead(TableName name, String... indexNames) {
        checkedIndexes.put(tableId(name), Arrays.asList(indexNames));
    }

    protected QueryContext queryContext() {
        return null; // Not needed
    }

    protected ChangeLevel getDefaultChangeLevel() {
        return ChangeLevel.TABLE;
    }

    protected void runAlter(String sql) {
        runAlter(getDefaultChangeLevel(), SCHEMA, sql);
    }

    protected void runAlter(ChangeLevel changeLevel, String sql) {
        runAlter(changeLevel, SCHEMA, sql);
    }

    protected void runAlter(ChangeLevel expectedChangeLevel, TableName name, UserTable newDefinition,
                            List<TableChange> columnChanges, List<TableChange> indexChanges) {
        ChangeLevel actual = ddlForAlter().alterTable(session(), name, newDefinition, columnChanges, indexChanges, queryContext());
        assertEquals("ChangeLevel", expectedChangeLevel, actual);
        updateAISGeneration();
    }

    protected void runRenameTable(TableName oldName, TableName newName) {
        AkibanInformationSchema aisCopy = AISCloner.clone(ddl().getAIS(session()));
        UserTable oldTable = aisCopy.getUserTable(oldName);
        assertNotNull("Found old table " + oldName, oldTable);
        AISTableNameChanger changer = new AISTableNameChanger(aisCopy.getUserTable(oldName), newName);
        changer.doChange();
        UserTable newTable = aisCopy.getUserTable(newName);
        assertNotNull("Found new table " + newName, oldTable);
        runAlter(ChangeLevel.METADATA, oldName, newTable, NO_CHANGES, NO_CHANGES);
    }

    protected void runRenameColumn(TableName tableName, String oldColName, String newColName) {
        AkibanInformationSchema aisCopy = AISCloner.clone(ddl().getAIS(session()));
        UserTable tableCopy = aisCopy.getUserTable(tableName);
        assertNotNull("Found table " + tableName, tableCopy);
        Column oldColumn = tableCopy.getColumn(oldColName);
        assertNotNull("Found old column " + oldColName, oldColumn);

        // Have to do this manually as parser doesn't support it, duplicates much of the work in AlterTableDDL
        List<Column> columns = new ArrayList<>(tableCopy.getColumns());
        tableCopy.dropColumns();
        for(Column column : columns) {
            Column.create(tableCopy, column, (column == oldColumn) ? newColName : null, null);
        }

        Column newColumn = tableCopy.getColumn(newColName);
        assertNotNull("Found new column " + newColName, newColumn);

        List<TableIndex> indexes = new ArrayList<>(tableCopy.getIndexes());
        for(TableIndex index : indexes) {
            if(index.containsTableColumn(tableName, oldColName)) {
                tableCopy.removeIndexes(Collections.singleton(index));
                TableIndex indexCopy = TableIndex.create(tableCopy, index);
                for(IndexColumn iCol : index.getKeyColumns()) {
                    IndexColumn.create(indexCopy, (iCol.getColumn() == oldColumn) ? newColumn : iCol.getColumn(), iCol, iCol.getPosition());
                }
            }
        }

        runAlter(ChangeLevel.METADATA,
                 tableName, tableCopy, Arrays.asList(TableChange.createModify(oldColName, newColName)), NO_CHANGES);
    }

    protected RowBase testRow(RowType type, Object... fields) {
        return new TestRow(type, fields);
    }

    protected void createAndLoadCAOI_PK_FK(boolean cPK, boolean aPK, boolean aFK, boolean oPK, boolean oFK, boolean iPK, boolean iFK) {
        throw new UnsupportedOperationException();
    }

    protected final void createAndLoadCAOI() {
        createAndLoadCAOI_PK_FK(true, true, true, true, true, true, true);
    }

    protected final void createAndLoadCAOI_PK(boolean cPK, boolean aPK, boolean oPK, boolean iPK) {
        createAndLoadCAOI_PK_FK(cPK, aPK, true, oPK, true, iPK, true);
    }

    protected final void createAndLoadCAOI_FK(boolean aFK, boolean oFK, boolean iFK) {
        createAndLoadCAOI_PK_FK(true, true, aFK, true, oFK, true, iFK);
    }


    // Note: Does not handle null index contents, check manually in that case
    private static class SingleColumnComparator implements Comparator<NewRow> {
        private final int colPos;

        SingleColumnComparator(int colPos) {
            this.colPos = colPos;
        }

        @Override
        public int compare(NewRow o1, NewRow o2) {
            Object col1 = o1.get(colPos);
            Object col2 = o2.get(colPos);
            if(col1 == null && col2 == null) {
                return 0;
            }
            if(col1 == null) {
                return -1;
            }
            if(col2 == null) {
                return +1;
            }
            return ((Comparable)col1).compareTo(col2);
        }
    }

    private void checkIndexContents(int tableID) {
        if(tableID == 0) {
            return;
        }

        updateAISGeneration();
        AkibanInformationSchema ais = ddl().getAIS(session());
        UserTable table = ais.getUserTable(tableID);
        List<NewRow> tableRows = new ArrayList<>(scanAll(scanAllRequest(tableID, true)));

        for(TableIndex index : table.getIndexesIncludingInternal()) {
            if(index.getKeyColumns().size() == 1) {
                int colPos = index.getKeyColumns().get(0).getColumn().getPosition();
                Collections.sort(tableRows, new SingleColumnComparator(colPos));

                List<NewRow> indexRows = scanAllIndex(index);

                if(tableRows.size() != indexRows.size()) {
                    assertEquals(index + " size does not match table size",
                                 tableRows.toString(), indexRows.toString());
                }

                for(int i = 0; i < tableRows.size(); ++i) {
                    Object tableObj = tableRows.get(i).get(colPos);
                    Object indexObj = indexRows.get(i).get(colPos);
                    assertEquals(index + " contents mismatch at row " + i,
                                 tableObj, indexObj);
                }
            }
        }
    }

    @After
    public final void doCheckAllSingleColumnIndexes() {
        for(UserTable table : ddl().getAIS(session()).getUserTables().values()) {
            if(!TableName.INFORMATION_SCHEMA.equals(table.getName().getSchemaName())) {
                checkIndexContents(table.getTableId());
            }
        }
    }
}