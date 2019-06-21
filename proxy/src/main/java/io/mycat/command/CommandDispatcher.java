/**
 * Copyright (C) <2019>  <chen junwen>
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU General Public License as published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with this program.  If
 * not, see <http://www.gnu.org/licenses/>.
 */
package io.mycat.command;

import static io.mycat.beans.mysql.packet.AuthPacket.calcLenencLength;

import io.mycat.beans.mysql.MySQLCommandType;
import io.mycat.config.MySQLServerCapabilityFlags;
import io.mycat.proxy.monitor.MycatMonitor;
import io.mycat.proxy.packet.MySQLPacket;
import io.mycat.proxy.session.MycatSession;
import java.util.HashMap;
import java.util.Map;

/**
 * @author jamie12221 date 2019-05-09 02:30
 *
 * command 报文解析分发
 **/
public interface CommandDispatcher extends LocalInFileRequestParseHelper,
    PrepareStatementParserHelper {

  default void handle(MycatSession mycat) {
    CommandDispatcher commandHandler = this;
    MycatMonitor.onCommandStart(mycat);
    try {
      MySQLPacket curPacket = mycat.currentProxyPayload();
      boolean isEmptyPayload = curPacket.readFinished();
      if (isEmptyPayload){
        MycatMonitor.onLoadDataLocalInFileEmptyPacketStart(mycat);
        commandHandler.handleContentOfFilenameEmptyOk();
        mycat.resetCurrentProxyPayload();
        MycatMonitor.onLoadDataLocalInFileEmptyPacketEnd(mycat);
        return;
      }else if (mycat.shouldHandleContentOfFilename()){
        MycatMonitor.onLoadDataLocalInFileContextStart(mycat);
        handleContentOfFilename(curPacket.readEOFStringBytes(),mycat);
        mycat.resetCurrentProxyPayload();
        MycatMonitor.onLoadDataLocalInFileContextEnd(mycat);
        return;
      }
      byte head = curPacket.getByte(curPacket.packetReadStartIndex());
      switch (head) {
        case MySQLCommandType.COM_SLEEP: {
          MycatMonitor.onSleepCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleSleep(mycat);
          MycatMonitor.onSleepCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_QUIT: {
          MycatMonitor.onQuitCommandStart(mycat);
          curPacket.readByte();
          commandHandler.handleQuit(mycat);
          MycatMonitor.onQuitCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_QUERY: {
          MycatMonitor.onQueryCommandStart(mycat);
          curPacket.readByte();
          byte[] bytes = curPacket.readEOFStringBytes();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleQuery(bytes, mycat);
          MycatMonitor.onQueryCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_INIT_DB: {
          MycatMonitor.onInitDbCommandStart(mycat);
          curPacket.readByte();
          String schema = curPacket.readEOFString();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleInitDb(schema, mycat);
          MycatMonitor.onInitDbCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_PING: {
          MycatMonitor.onPingCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePing(mycat);
          MycatMonitor.onPingCommandEnd(mycat);
          break;
        }

        case MySQLCommandType.COM_FIELD_LIST: {
          MycatMonitor.onFieldListCommandStart(mycat);
          curPacket.readByte();
          String table = curPacket.readNULString();
          String field = curPacket.readEOFString();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleFieldList(table, field, mycat);
          MycatMonitor.onFieldListCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_SET_OPTION: {
          MycatMonitor.onSetOptionCommandStart(mycat);
          curPacket.readByte();
          boolean option = curPacket.readFixInt(2) == 1;
          mycat.resetCurrentProxyPayload();
          commandHandler.handleSetOption(option, mycat);
          MycatMonitor.onSetOptionCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_STMT_PREPARE: {
          MycatMonitor.onPrepareCommandStart(mycat);
          curPacket.readByte();
          byte[] bytes = curPacket.readEOFStringBytes();
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePrepareStatement(bytes, mycat);
          MycatMonitor.onPrepareCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_STMT_SEND_LONG_DATA: {
          MycatMonitor.onSendLongDataCommandStart(mycat);
          curPacket.readByte();
          long statementId = curPacket.readFixInt(4);
          int paramId = (int) curPacket.readFixInt(2);
          byte[] data = curPacket.readEOFStringBytes();
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePrepareStatementLongdata(statementId, paramId, data, mycat);
          MycatMonitor.onSendLongDataCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_STMT_EXECUTE: {
          MycatMonitor.onExecuteCommandStart(mycat);
          try {
            byte[] rawPayload = curPacket.getEOFStringBytes(curPacket.packetReadStartIndex());
            curPacket.readByte();
            long statementId = curPacket.readFixInt(4);
            byte flags = curPacket.readByte();
            long iteration = curPacket.readFixInt(4);
            assert iteration == 1;
            int numParams = getNumParamsByStatementId(statementId);
            byte[] rest = curPacket.readEOFStringBytes();
            mycat.resetCurrentProxyPayload();
            commandHandler
                .handlePrepareStatementExecute(rawPayload, statementId, flags, numParams, rest,
                    mycat);
            break;
          } finally {
            MycatMonitor.onExecuteCommandEnd(mycat);
          }
        }
        case MySQLCommandType.COM_STMT_CLOSE: {
          MycatMonitor.onCloseCommandStart(mycat);
          curPacket.readByte();
          long statementId = curPacket.readFixInt(4);
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePrepareStatementClose(statementId, mycat);
          MycatMonitor.onCloseCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_STMT_FETCH: {
          MycatMonitor.onFetchCommandStart(mycat);
          curPacket.readByte();
          long statementId = curPacket.readFixInt(4);
          long row = curPacket.readFixInt(4);
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePrepareStatementFetch(statementId, row);
          MycatMonitor.onFetchCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_STMT_RESET: {
          MycatMonitor.onResetCommandStart(mycat);
          curPacket.readByte();
          long statementId = curPacket.readFixInt(4);
          mycat.resetCurrentProxyPayload();
          commandHandler.handlePrepareStatementReset(statementId, mycat);
          MycatMonitor.onResetCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_CREATE_DB: {
          MycatMonitor.onCreateDbCommandStart(mycat);
          curPacket.readByte();
          String schema = curPacket.readEOFString();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleCreateDb(schema, mycat);
          MycatMonitor.onCreateDbCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_DROP_DB: {
          MycatMonitor.onDropDbCommandStart(mycat);
          curPacket.readByte();
          String schema = curPacket.readEOFString();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleDropDb(schema, mycat);
          MycatMonitor.onDropDbCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_REFRESH: {
          MycatMonitor.onRefreshCommandStart(mycat);
          curPacket.readByte();
          byte subCommand = curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleRefresh(subCommand, mycat);
          MycatMonitor.onRefreshCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_SHUTDOWN: {
          MycatMonitor.onShutdownCommandStart(mycat);
          curPacket.readByte();
          try {
            if (!curPacket.readFinished()) {
              byte shutdownType = curPacket.readByte();
              mycat.resetCurrentProxyPayload();
              commandHandler.handleShutdown(shutdownType, mycat);
            } else {
              mycat.resetCurrentProxyPayload();
              commandHandler.handleShutdown(0, mycat);
            }
          } finally {
            MycatMonitor.onShutdownCommandEnd(mycat);
          }
          break;
        }
        case MySQLCommandType.COM_STATISTICS: {
          MycatMonitor.onStatisticsCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleStatistics(mycat);
          MycatMonitor.onStatisticsCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_PROCESS_INFO: {
          MycatMonitor.onProcessInfoCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleProcessInfo(mycat);
          MycatMonitor.onProcessInfoCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_CONNECT: {
          MycatMonitor.onConnectCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleConnect(mycat);
          MycatMonitor.onConnectCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_PROCESS_KILL: {
          MycatMonitor.onProcessKillCommandStart(mycat);
          curPacket.readByte();
          long connectionId = curPacket.readFixInt(4);
          mycat.resetCurrentProxyPayload();
          commandHandler.handleProcessKill(connectionId, mycat);
          MycatMonitor.onProcessKillCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_DEBUG: {
          MycatMonitor.onDebugCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleDebug(mycat);
          MycatMonitor.onDebugCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_TIME: {
          MycatMonitor.onTimeCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleTime(mycat);
          MycatMonitor.onTimeCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_DELAYED_INSERT: {
          MycatMonitor.onDelayedInsertCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleTime(mycat);
          MycatMonitor.onDelayedInsertCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_CHANGE_USER: {
          MycatMonitor.onChangeUserCommandStart(mycat);
          curPacket.readByte();
          try {
            String userName = curPacket.readNULString();
            String authResponse = null;
            String schemaName = null;
            Integer characterSet = null;
            String authPluginName = null;
            HashMap<String, String> clientConnectAttrs = new HashMap<>();
            int capabilities = mycat.getCapabilities();
            if (MySQLServerCapabilityFlags.isCanDo41Anthentication(capabilities)) {
              byte len = curPacket.readByte();
              authResponse = curPacket.readFixString(len);
            } else {
              authResponse = curPacket.readNULString();
            }
            schemaName = curPacket.readNULString();
            if (!curPacket.readFinished()) {
              characterSet = (int) curPacket.readFixInt(2);
              if (MySQLServerCapabilityFlags.isPluginAuth(capabilities)) {
                authPluginName = curPacket.readNULString();
              }
              if (MySQLServerCapabilityFlags.isConnectAttrs(capabilities)) {
                long kvAllLength = curPacket.readLenencInt();
                if (kvAllLength != 0) {
                  clientConnectAttrs = new HashMap<>();
                }
                int count = 0;
                while (count < kvAllLength) {
                  String k = curPacket.readLenencString();
                  String v = curPacket.readLenencString();
                  count += k.length();
                  count += v.length();
                  count += calcLenencLength(k.length());
                  count += calcLenencLength(v.length());
                  clientConnectAttrs.put(k, v);
                }
              }
            }
            mycat.resetCurrentProxyPayload();
            commandHandler
                .handleChangeUser(userName, authResponse, schemaName, characterSet, authPluginName,
                    clientConnectAttrs, mycat);
          } finally {
            MycatMonitor.onChangeUserCommandEnd(mycat);
          }
          break;
        }
        case MySQLCommandType.COM_RESET_CONNECTION: {
          MycatMonitor.onResetCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleResetConnection(mycat);
          MycatMonitor.onResetCommandEnd(mycat);
          break;
        }
        case MySQLCommandType.COM_DAEMON: {
          MycatMonitor.onDaemonCommandStart(mycat);
          curPacket.readByte();
          mycat.resetCurrentProxyPayload();
          commandHandler.handleDaemon(mycat);
          MycatMonitor.onDaemonCommandEnd(mycat);
          break;
        }
        default: {
          assert false;
        }
      }
    } finally {
      MycatMonitor.onCommandEnd(mycat);
    }
  }

  void handleQuery(byte[] sql, MycatSession session);

  void handleSleep(MycatSession session);

  void handleQuit(MycatSession session);

  void handleInitDb(String db, MycatSession session);

  void handlePing(MycatSession session);

  void handleFieldList(String table, String filedWildcard, MycatSession session);

  void handleSetOption(boolean on, MycatSession session);

  void handleCreateDb(String schemaName, MycatSession session);

  void handleDropDb(String schemaName, MycatSession session);

  void handleRefresh(int subCommand, MycatSession session);

  void handleShutdown(int shutdownType, MycatSession session);

  void handleStatistics(MycatSession session);

  void handleProcessInfo(MycatSession session);

  void handleConnect(MycatSession session);

  void handleProcessKill(long connectionId, MycatSession session);

  void handleDebug(MycatSession session);

  void handleTime(MycatSession session);

  void handleChangeUser(String userName, String authResponse, String schemaName,
      int charsetSet, String authPlugin, Map<String, String> clientConnectAttrs,
      MycatSession session);

  void handleDelayedInsert(MycatSession session);

  void handleResetConnection(MycatSession session);

  void handleDaemon(MycatSession session);

  abstract class AbstractCommandHandler implements CommandDispatcher {


    public void handleSleep(MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleRefresh(int subCommand, MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleShutdown(int shutdownType, MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleConnect(MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleDebug(MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleTime(MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleDelayedInsert(MycatSession session) {
      session.writeErrorEndPacket();
    }


    public void handleDaemon(MycatSession session) {
      session.writeErrorEndPacket();
    }


  }
}
