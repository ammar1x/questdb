/*******************************************************************************
 *     ___                  _   ____  ____
 *    / _ \ _   _  ___  ___| |_|  _ \| __ )
 *   | | | | | | |/ _ \/ __| __| | | |  _ \
 *   | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *    \__\_\\__,_|\___||___/\__|____/|____/
 *
 *  Copyright (c) 2014-2019 Appsicle
 *  Copyright (c) 2019-2020 QuestDB
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 ******************************************************************************/

package io.questdb.cairo;

import io.questdb.cairo.sql.*;
import io.questdb.griffin.SqlException;
import io.questdb.log.Log;
import io.questdb.log.LogFactory;
import io.questdb.std.*;
import io.questdb.std.str.CharSink;
import io.questdb.std.str.DirectCharSequence;
import io.questdb.tasks.TableWriterTask;

public class AlterTableImpl implements AlterStatement, AlterStatementAddColumnStatement, AlterStatementRenameColumnStatement, AlterStatementDropColumnStatement, AlterStatementChangePartitionStatement, Mutable {
    private final static Log LOG = LogFactory.getLog(AlterTableImpl.class);
    private short command;
    private String tableName;
    private int tableId;
    private int tableNamePosition;
    private CharSequenceList charSequenceList;

    private final ObjCharSequenceList objCharList = new ObjCharSequenceList();
    private final DirectCharSequenceList directCharList = new DirectCharSequenceList();
    private final LongList longList = new LongList();
    private final ExceptionSinkAdapter exceptionSinkAdapter = new ExceptionSinkAdapter();

    @Override
    public void apply(TableWriter tableWriter, boolean acceptStructureChange) throws SqlException, TableStructureChangesException {
        try {
            switch (command) {
                case DO_NOTHING:
                    // Lock cannot be applied on another thread.
                    // it is applied at the SQL compilation time
                    break;
                case ADD_COLUMN:
                    applyAddColumn(tableWriter);
                    break;
                case DROP_PARTITION:
                    applyDropPartition(tableWriter);
                    break;
                case ATTACH_PARTITION:
                    applyAttachPartition(tableWriter);
                    break;
                case ADD_INDEX:
                    applyAddIndex(tableWriter);
                    break;
                case ADD_SYMBOL_CACHE:
                    applySetSymbolCache(tableWriter, true);
                    break;
                case REMOVE_SYMBOL_CACHE:
                    applySetSymbolCache(tableWriter, false);
                    break;
                case DROP_COLUMN:
                    if (!acceptStructureChange) {
                        throw TableStructureChangesException.INSTANCE;
                    }
                    applyDropColumn(tableWriter);
                    break;
                case RENAME_COLUMN:
                    if (!acceptStructureChange) {
                        throw TableStructureChangesException.INSTANCE;
                    }
                    applyRenameColumn(tableWriter);
                    break;
                case SET_PARAM_MAX_UNCOMMITTED_ROWS:
                    applyParamUncommittedRows(tableWriter);
                    break;
                case SET_PARAM_COMMIT_LAG:
                    applyParamCommitLag(tableWriter);
                    break;
                default:
                    throw CairoException.instance(0).put("Invalid alter table command [code=").put(command).put(']');
            }
        } catch (EntryUnavailableException | SqlException ex) {
            throw ex;
        } catch (CairoException e2) {
            LOG.error().$("table '")
                    .$(tableName)
                    .$("' could not be altered [")
                    .$(e2.getErrno())
                    .$("] ")
                    .$(e2.getFlyweightMessage());

            throw SqlException.$(tableNamePosition, "table '")
                    .put(tableName)
                    .put("' could not be altered: [")
                    .put(e2.getErrno())
                    .put("] ")
                    .put(e2.getFlyweightMessage());
        }
    }

    @Override
    public void clear() {
        command = DO_NOTHING;
        objCharList.clear();
        directCharList.clear();
        charSequenceList = objCharList;
        longList.clear();
    }

    @Override
    public CharSequence getTableName() {
        return tableName;
    }

    @Override
    public int getTableNamePosition() {
        return tableNamePosition;
    }

    public void deserialize(TableWriterTask event) {
        clear();

        tableName = event.getTableName();
        long readPtr = event.getData();
        command = Unsafe.getUnsafe().getShort(readPtr);
        readPtr += 2;
        tableNamePosition = Unsafe.getUnsafe().getInt(readPtr);
        readPtr += 4;
        int longSize = Unsafe.getUnsafe().getInt(readPtr);
        readPtr += 4;
        for(int i = 0; i < longSize; i++) {
            longList.add(Unsafe.getUnsafe().getLong(readPtr));
            readPtr += 8;
        }

        directCharList.of(readPtr);
        charSequenceList = directCharList;
    }

    @Override
    public void serialize(TableWriterTask event) {
        event.of(TableWriterTask.TSK_ALTER_TABLE, tableId, tableName);
        event.putShort(command);
        event.putInt(tableNamePosition);
        event.putInt(longList.size());
        for(int i = 0, n = longList.size(); i < n; i++) {
            event.putLong(longList.getQuick(i));
        }

        event.putInt(objCharList.size());
        for(int i = 0, n = objCharList.size(); i < n; i++) {
            event.putStr(objCharList.getStrA(i));
        }
    }

    public AlterStatement doNothing() {
        this.command = DO_NOTHING;
        this.tableName = null;
        return this;
    }

    public AlterStatementAddColumnStatement ofAddColumn(
            int tableNamePosition,
            String tableName,
            int tableId
    ) {
        this.command = ADD_COLUMN;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        return this;
    }

    @Override
    public AlterStatementAddColumnStatement ofAddColumn(CharSequence columnName, int type, int symbolCapacity, boolean cache, boolean indexed, int indexValueBlockCapacity) {
        this.objCharList.add(columnName);
        this.longList.add(type);
        this.longList.add(symbolCapacity);
        this.longList.add(cache ? 1 : -1);
        this.longList.add(indexed ? 1 : -1);
        this.longList.add(indexValueBlockCapacity);
        return this;
    }

    public AlterStatement ofAddIndex(int tableNamePosition, String tableName, int tableId, CharSequence columnName, int indexValueBlockSize) {
        this.command = ADD_INDEX;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        this.objCharList.add(columnName);
        this.longList.add(indexValueBlockSize);
        return this;
    }

    public AlterStatementChangePartitionStatement ofAttachPartition(int tableNamePosition, String tableName, int tableId) {
        this.command = ATTACH_PARTITION;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        return this;
    }

    public AlterStatement ofCacheSymbol(int tableNamePosition, String tableName, int tableId, CharSequence columnName) {
        this.command = ADD_SYMBOL_CACHE;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        this.objCharList.add(columnName);
        return this;
    }

    @Override
    public void ofPartition(long timestamp) {
        this.longList.add(timestamp);
    }

    @Override
    public AlterStatementRenameColumnStatement ofRenameColumn(CharSequence columnName, CharSequence newName) {
        this.objCharList.add(columnName);
        this.objCharList.add(newName);
        return null;
    }

    @Override
    public AlterStatementDropColumnStatement ofDropColumn(CharSequence columnName) {
        this.objCharList.add(columnName);
        return this;
    }

    public AlterStatementDropColumnStatement ofDropColumn(int tableNamePosition, String tableName, int tableId) {
        this.command = DROP_COLUMN;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        return this;
    }

    public AlterStatementChangePartitionStatement ofDropPartition(int tableNamePosition, String tableName, int tableId) {
        this.command = DROP_PARTITION;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        return this;
    }

    public AlterStatement ofRemoveCacheSymbol(int tableNamePosition, String tableName, int tableId, CharSequence columnName) {
        this.command = REMOVE_SYMBOL_CACHE;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        this.objCharList.add(columnName);
        return this;
    }

    public AlterStatementRenameColumnStatement ofRenameColumn(int tableNamePosition, String tableName, int tableId) {
        this.command = RENAME_COLUMN;
        this.tableNamePosition = tableNamePosition;
        this.tableName = tableName;
        this.tableId = tableId;
        return this;
    }

    public AlterStatement ofSetParamCommitLag(String tableName, int tableId, long commitLag) {
        this.command = SET_PARAM_COMMIT_LAG;
        this.tableName = tableName;
        this.longList.add(commitLag);
        this.tableId = tableId;
        return this;
    }

    public AlterStatement ofSetParamUncommittedRows(String tableName, int tableId, int maxUncommittedRows) {
        this.command = SET_PARAM_MAX_UNCOMMITTED_ROWS;
        this.tableName = tableName;
        this.longList.add(maxUncommittedRows);
        this.tableId = tableId;
        return this;
    }

    private void applyAddColumn(TableWriter tableWriter) throws SqlException {
        int lParam = 0;
        for (int i = 0, n = charSequenceList.size(); i < n; i++) {
            CharSequence columnName = charSequenceList.getStrA(i);
            int type = (int) longList.get(lParam++);
            int symbolCapacity = (int) longList.get(lParam++);
            boolean symbolCacheFlag = longList.get(lParam++) > 0;
            boolean isIndexed = longList.get(lParam++) > 0;
            int indexValueBlockCapacity = (int) longList.get(lParam++);
            try {
                tableWriter.addColumn(
                        columnName,
                        type,
                        symbolCapacity,
                        symbolCacheFlag,
                        isIndexed,
                        indexValueBlockCapacity,
                        false
                );
            } catch (CairoException e) {
                LOG.error().$("Cannot add column '").$(tableWriter.getTableName()).$('.').$(columnName).$("'. Exception: ").$((Sinkable) e).$();
                throw SqlException.$(tableNamePosition, "could not add column [error=").put(e.getFlyweightMessage())
                        .put(", errno=").put(e.getErrno())
                        .put(']');
            }
        }
    }

    private void applyAddIndex(TableWriter tableWriter) throws SqlException {
        CharSequence columnName = charSequenceList.getStrA(0);
        try {
            int indexValueBlockSize = (int) longList.get(0);
            tableWriter.addIndex(columnName, indexValueBlockSize);
        } catch (CairoException e) {
            throw SqlException.position(tableNamePosition).put(e.getFlyweightMessage())
                    .put("[errno=").put(e.getErrno()).put(']');
        }
    }

    private void applyAttachPartition(TableWriter tableWriter) throws SqlException {
        for (int i = 0, n = longList.size(); i < n; i++) {
            long partitionTimestamp = longList.getQuick(i);
            try {
                int statusCode = tableWriter.attachPartition(partitionTimestamp);
                switch (statusCode) {
                    case StatusCode.OK:
                        break;
                    case StatusCode.CANNOT_ATTACH_MISSING_PARTITION:
                        throw putPartitionName(
                                SqlException.$(tableNamePosition, "attach partition failed, folder '"),
                                tableWriter.getPartitionBy(),
                                partitionTimestamp)
                                .put("' does not exist");
                    case StatusCode.TABLE_HAS_SYMBOLS:
                        throw SqlException.$(tableNamePosition, "attaching partitions to tables with symbol columns not supported");
                    case StatusCode.PARTITION_EMPTY:
                        throw putPartitionName(
                                SqlException.$(tableNamePosition, "failed to attach partition '"),
                                tableWriter.getPartitionBy(),
                                partitionTimestamp)
                                .put("', data does not correspond to the partition folder or partition is empty");
                    case StatusCode.PARTITION_ALREADY_ATTACHED:
                        throw putPartitionName(
                                SqlException.$(tableNamePosition, "failed to attach partition '"),
                                tableWriter.getPartitionBy(),
                                partitionTimestamp)
                                .put("', partition already attached to the table");
                    default:
                        throw putPartitionName(
                                SqlException.$(tableNamePosition, "attach partition  '"),
                                tableWriter.getPartitionBy(),
                                partitionTimestamp)
                                .put(statusCode);
                }
            } catch (CairoException e) {
                LOG.error().$("failed to drop partition [table=").$(tableName)
                        .$(",ts=").$ts(partitionTimestamp)
                        .$(",errno=").$(e.getErrno())
                        .$(",error=").$(e.getFlyweightMessage())
                        .I$();

                throw e;
            }
        }
    }

    private SqlException putPartitionName(SqlException ex, int partitionBy, long timestamp) {
        TableUtils.setSinkForPartition(exceptionSinkAdapter.of(ex), partitionBy, timestamp, false);
        return ex;
    }

    private void applyDropColumn(TableWriter writer) throws SqlException {
        for (int i = 0, n = charSequenceList.size(); i < n; i++) {
            CharSequence columnName = charSequenceList.getStrA(i);
            RecordMetadata metadata = writer.getMetadata();
            if (metadata.getColumnIndexQuiet(columnName) == -1) {
                throw SqlException.invalidColumn(tableNamePosition, columnName);
            }
            try {
                writer.removeColumn(columnName);
            } catch (CairoException e) {
                LOG.error().$("cannot drop column '").$(writer.getTableName()).$('.').$(columnName).$("'. Exception: ").$((Sinkable) e).$();
                throw SqlException.$(tableNamePosition, "cannot drop column. Try again later [errno=").put(e.getErrno()).put(']');
            }
        }
    }

    private void applyDropPartition(TableWriter tableWriter) throws SqlException {
        for (int i = 0, n = longList.size(); i < n; i++) {
            long partitionTimestamp = longList.getQuick(i);
            try {
                if (!tableWriter.removePartition(partitionTimestamp)) {
                    throw putPartitionName(SqlException.$(tableNamePosition, "could not remove partition '"),
                            tableWriter.getPartitionBy(),
                            partitionTimestamp).put('\'');
                }
            } catch (CairoException e) {
                LOG.error().$("failed to drop partition [table=").$(tableName)
                        .$(",ts=").$ts(partitionTimestamp)
                        .$(",errno=").$(e.getErrno())
                        .$(",error=").$(e.getFlyweightMessage())
                        .I$();

                throw SqlException.$(tableNamePosition, e.getFlyweightMessage());
            }
        }
    }

    private void applyParamCommitLag(TableWriter tableWriter) {
        long commitLag = longList.get(0);
        tableWriter.setMetaCommitLag(commitLag);
    }

    private void applyParamUncommittedRows(TableWriter tableWriter) {
        int maxUncommittedRows = (int) longList.get(0);
        tableWriter.setMetaMaxUncommittedRows(maxUncommittedRows);
    }

    private void applyRenameColumn(TableWriter writer) throws SqlException {
        // To not store 2 var len fields, store only new name as CharSequence
        // and index of existing column store as
        int i = 0, n = charSequenceList.size();
        while (i < n) {
            CharSequence columnName = charSequenceList.getStrA(i++);
            CharSequence newName = charSequenceList.getStrB(i++);
            try {
                writer.renameColumn(columnName, newName);
            } catch (CairoException e) {
                LOG.error().$("cannot rename column '").$(writer.getTableName()).$('.').$(columnName).$("'. Exception: ").$((Sinkable) e).$();
                throw SqlException.$(tableNamePosition, "cannot rename column \"").put(columnName).put("\"; ").put(e.getFlyweightMessage());
            }
        }
    }

    private void applySetSymbolCache(TableWriter tableWriter, boolean isCacheOn) throws SqlException {
        CharSequence columnName = charSequenceList.getStrA(0);
        int columnIndex = tableWriter.getMetadata().getColumnIndexQuiet(columnName);
        if (columnIndex == -1) {
            throw SqlException.invalidColumn(tableNamePosition, columnName);
        }
        tableWriter.changeCacheFlag(columnIndex, isCacheOn);
    }

    private static class ExceptionSinkAdapter implements CharSink {
        private SqlException ex;

        ExceptionSinkAdapter of(SqlException ex) {
            this.ex = ex;
            return this;
        }

        @Override
        public char[] getDoubleDigitsBuffer() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CharSink put(char c) {
            ex.put(c);
            return this;
        }

        @Override
        public CharSink put(long c) {
            ex.put(c);
            return this;
        }

        @Override
        public CharSink put(int c) {
            ex.put(c);
            return this;
        }
    }

    interface CharSequenceList extends Mutable {
        CharSequence getStrA(int i);
        CharSequence getStrB(int i);
        int size();
    }

    private static class ObjCharSequenceList implements CharSequenceList {
        private final ObjList<CharSequence> strings = new ObjList<>();

        public void add(CharSequence ch) {
            strings.add(ch);
        }

        @Override
        public void clear() {
            strings.clear();
        }

        public CharSequence getStrA(int i) {
            return strings.get(i);
        }

        @Override
        public CharSequence getStrB(int i) {
            return strings.get(i);
        }

        @Override
        public int size() {
            return strings.size();
        }
    }

    private static class DirectCharSequenceList implements CharSequenceList {
        private final LongList offsets = new LongList();
        private final DirectCharSequence strA = new DirectCharSequence();
        private final DirectCharSequence strB = new DirectCharSequence();

        @Override
        public void clear() {
            offsets.clear();
        }

        public long of(long address) {
            long initialAddress = address;
            int size = Unsafe.getUnsafe().getInt(address);
            address += 4;
            for(int i = 0; i < size; i++) {
                int stringSize = 2 * Unsafe.getUnsafe().getInt(address);
                address += 4;
                offsets.add(address, address + stringSize);
                address += stringSize;
            }
            return address - initialAddress;
        }

        public CharSequence getStrA(int i) {
            long lo = offsets.get(i * 2);
            long hi = offsets.get(i * 2 + 1);
            strA.of(lo, hi);
            return strA;
        }

        @Override
        public CharSequence getStrB(int i) {
            long lo = offsets.get(i * 2);
            long hi = offsets.get(i * 2 + 1);
            strB.of(lo, hi);
            return strB;
        }

        @Override
        public int size() {
            return offsets.size() / 2;
        }
    }
}