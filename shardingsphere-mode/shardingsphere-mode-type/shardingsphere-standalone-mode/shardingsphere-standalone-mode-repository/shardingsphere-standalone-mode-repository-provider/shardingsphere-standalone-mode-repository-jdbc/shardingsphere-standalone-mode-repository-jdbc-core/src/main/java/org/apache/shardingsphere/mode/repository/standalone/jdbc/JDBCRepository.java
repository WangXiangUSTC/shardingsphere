/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.shardingsphere.mode.repository.standalone.jdbc;

import com.google.common.base.Strings;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.mode.repository.standalone.StandalonePersistRepository;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;

/**
 * JDBC repository.
 */
@Slf4j
public abstract class JDBCRepository implements StandalonePersistRepository {
    
    private static final String SEPARATOR = "/";
    
    private Connection connection;
    
    @SneakyThrows
    protected void initTable(final String jdbcUrl, final String user, final String password) {
        connection = DriverManager.getConnection(jdbcUrl, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS repository");
            statement.execute("CREATE TABLE repository(id varchar(36) PRIMARY KEY, key TEXT, value TEXT, parent TEXT)");
        }
    }
    
    @Override
    public String get(final String key) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT value FROM repository WHERE key = ?")) {
            preparedStatement.setString(1, key);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                if (resultSet.next()) {
                    return resultSet.getString("value");
                }
            }
        } catch (final SQLException ex) {
            log.error("Get {} data by key: {} failed", getType(), key, ex);
        }
        return "";
    }
    
    @Override
    public List<String> getChildrenKeys(final String key) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("SELECT key FROM repository WHERE parent = ?")) {
            preparedStatement.setString(1, key);
            try (ResultSet resultSet = preparedStatement.executeQuery()) {
                List<String> resultChildren = new LinkedList<>();
                while (resultSet.next()) {
                    String childrenKey = resultSet.getString("key");
                    if (Strings.isNullOrEmpty(childrenKey)) {
                        continue;
                    }
                    int lastIndexOf = childrenKey.lastIndexOf(SEPARATOR);
                    resultChildren.add(childrenKey.substring(lastIndexOf + 1));
                }
                return new ArrayList<>(resultChildren);
            }
        } catch (final SQLException ex) {
            log.error("Get children {} data by key: {} failed", getType(), key, ex);
        }
        return Collections.emptyList();
    }
    
    @Override
    public void persist(final String key, final String value) {
        String[] paths = Arrays.stream(key.split(SEPARATOR)).filter(each -> !Strings.isNullOrEmpty(each)).toArray(String[]::new);
        String tempPrefix = "";
        String parent = SEPARATOR;
        try {
            // Create key level directory recursively.
            for (int i = 0; i < paths.length - 1; i++) {
                String tempKey = tempPrefix + SEPARATOR + paths[i];
                String tempKeyVal = get(tempKey);
                if (Strings.isNullOrEmpty(tempKeyVal)) {
                    if (i != 0) {
                        parent = tempPrefix;
                    }
                    insert(tempKey, "", parent);
                }
                tempPrefix = tempKey;
                parent = tempKey;
            }
            String keyValue = get(key);
            if (Strings.isNullOrEmpty(keyValue)) {
                insert(key, value, parent);
            } else {
                update(key, value);
            }
        } catch (final SQLException ex) {
            log.error("Persist {} data to key: {} failed", getType(), key, ex);
        }
    }
    
    private void insert(final String key, final String value, final String parent) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO repository VALUES(?, ?, ?, ?)")) {
            preparedStatement.setString(1, UUID.randomUUID().toString());
            preparedStatement.setString(2, key);
            preparedStatement.setString(3, value);
            preparedStatement.setString(4, parent);
            preparedStatement.executeUpdate();
        }
    }
    
    private void update(final String key, final String value) throws SQLException {
        try (PreparedStatement preparedStatement = connection.prepareStatement("UPDATE repository SET value = ? WHERE key = ?")) {
            preparedStatement.setString(1, value);
            preparedStatement.setString(2, key);
            preparedStatement.executeUpdate();
        }
    }
    
    @Override
    public void delete(final String key) {
        try (PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM repository WHERE key = ?")) {
            preparedStatement.setString(1, key);
            preparedStatement.executeUpdate();
        } catch (final SQLException ex) {
            log.error(String.format("Delete %s data by key: {} failed", getType()), key, ex);
        }
    }
    
    @Override
    public void close() {
        try {
            if (null != connection) {
                connection.close();
            }
        } catch (final SQLException ex) {
            log.error(String.format("Failed to release %s database resources.", getType()), ex);
        }
    }
}