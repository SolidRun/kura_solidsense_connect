/*******************************************************************************
 * Copyright (c) 2022, 2023 Eurotech and/or its affiliates and others
 * 
 * This program and the accompanying materials are made
 * available under the terms of the Eclipse Public License 2.0
 * which is available at https://www.eclipse.org/legal/epl-2.0/
 * 
 * SPDX-License-Identifier: EPL-2.0
 * 
 * Contributors:
 *  Eurotech
 *******************************************************************************/
package org.eclipse.kura.internal.db.sqlite.provider;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.eclipse.kura.KuraException;
import org.eclipse.kura.configuration.ConfigurationService;
import org.eclipse.kura.crypto.CryptoService;
import org.eclipse.kura.util.configuration.Property;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.sqlite.SQLiteConfig.HexKeyMode;

class SqliteDbServiceOptions {

    private static final Logger logger = LoggerFactory.getLogger(SqliteDbServiceOptions.class);

    public enum Mode {
        IN_MEMORY,
        PERSISTED
    }

    public enum JournalMode {
        ROLLBACK_JOURNAL,
        WAL
    }

    public enum EncryptionKeyFormat {

        ASCII,
        HEX_SSE,
        HEX_SQLCIPHER;

        public HexKeyMode toHexKeyMode() {
            if (this == EncryptionKeyFormat.ASCII) {
                return HexKeyMode.NONE;
            } else if (this == EncryptionKeyFormat.HEX_SSE) {
                return HexKeyMode.SSE;
            } else if (this == EncryptionKeyFormat.HEX_SQLCIPHER) {
                return HexKeyMode.SQLCIPHER;
            } else {
                throw new IllegalStateException();
            }
        }
    }

    public static class EncryptionKeySpec {

        private final String key;
        private final EncryptionKeyFormat format;

        public EncryptionKeySpec(final String key, final EncryptionKeyFormat format) {
            this.key = key;
            this.format = format;
        }

        public String getKey() {
            return key;
        }

        public EncryptionKeyFormat getFormat() {
            return format;
        }

        @Override
        public int hashCode() {
            return Objects.hash(format, key);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof EncryptionKeySpec)) {
                return false;
            }
            EncryptionKeySpec other = (EncryptionKeySpec) obj;
            return format == other.format && Objects.equals(key, other.key);
        }

    }

    private static final Property<String> MODE_PROPERTY = new Property<>("db.mode", Mode.PERSISTED.name());
    private static final Property<String> PATH_PROPERTY = new Property<>("db.path", "mydb");
    private static final Property<Integer> CONNECTION_POOL_MAX_SIZE_PROPERTY = new Property<>(
            "db.connection.pool.max.size", 10);
    private static final Property<String> JOURNAL_MODE_PROPERTY = new Property<>("db.journal.mode",
            JournalMode.WAL.name());
    private static final Property<Long> DEFRAG_INTERVAL_SECONDS_PROPERTY = new Property<>("db.defrag.interval.seconds",
            900L);
    private static final Property<Long> WAL_CHECKPOINT_INTERVAL_SECONDS_PROPERTY = new Property<>(
            "db.wal.checkpoint.interval.seconds", 600L);
    private static final Property<Boolean> DEBUG_SHELL_ACCESS_ENABLED_PROPERTY = new Property<>(
            "debug.shell.access.enabled", false);
    private static final Property<String> ENCRYPTION_KEY_PROPERTY = new Property<>("db.key", String.class);
    private static final Property<String> ENCRYPTION_KEY_FORMAT_PROPERTY = new Property<>("db.key.format",
            EncryptionKeyFormat.ASCII.name());
    private static final Property<String> KURA_SERVICE_PID_PROPERTY = new Property<>(
            ConfigurationService.KURA_SERVICE_PID, "sqlitedb");

    private final Mode mode;
    private final String path;
    private final String kuraServicePid;
    private final boolean isDebugShellAccessEnabled;
    private final long defragIntervalSeconds;
    private final long walCheckpointIntervalSeconds;
    private final int maxConnectionPoolSize;
    private final JournalMode journalMode;
    private final Optional<String> encryptionKey;
    private final EncryptionKeyFormat encryptionKeyFormat;

    public SqliteDbServiceOptions(Map<String, Object> properties) {
        this.mode = extractMode(properties);
        this.path = PATH_PROPERTY.get(properties);
        this.kuraServicePid = KURA_SERVICE_PID_PROPERTY.get(properties);
        this.maxConnectionPoolSize = CONNECTION_POOL_MAX_SIZE_PROPERTY.get(properties);
        this.defragIntervalSeconds = DEFRAG_INTERVAL_SECONDS_PROPERTY.get(properties);
        this.walCheckpointIntervalSeconds = WAL_CHECKPOINT_INTERVAL_SECONDS_PROPERTY.get(properties);
        this.journalMode = extractJournalMode(properties);
        this.isDebugShellAccessEnabled = DEBUG_SHELL_ACCESS_ENABLED_PROPERTY.get(properties);
        this.encryptionKey = ENCRYPTION_KEY_PROPERTY.getOptional(properties).filter(s -> !s.trim().isEmpty());
        this.encryptionKeyFormat = extractEncryptionKeyFormat(properties);
    }

    public Mode getMode() {
        return mode;
    }

    public String getPath() {
        return path;
    }

    public int getConnectionPoolMaxSize() {
        return this.maxConnectionPoolSize;
    }

    public boolean isDebugShellAccessEnabled() {
        return isDebugShellAccessEnabled;
    }

    public long getDefragIntervalSeconds() {
        return defragIntervalSeconds;
    }

    public JournalMode getJournalMode() {
        return journalMode;
    }

    public String getKuraServicePid() {
        return kuraServicePid;
    }

    public long getWalCheckpointIntervalSeconds() {
        return walCheckpointIntervalSeconds;
    }

    public EncryptionKeyFormat getEncryptionKeyFormat() {
        return encryptionKeyFormat;
    }

    public Optional<EncryptionKeySpec> getEncryptionKey(final CryptoService cryptoService) throws KuraException {
        if (this.encryptionKey.isPresent()) {
            final String decrypted = new String(cryptoService.decryptAes(this.encryptionKey.get().toCharArray()));

            return Optional.of(new EncryptionKeySpec(decrypted, getEncryptionKeyFormat()));
        } else {
            return Optional.empty();
        }
    }

    public boolean isPeriodicWalCheckpointEnabled() {
        return this.mode != Mode.IN_MEMORY && this.journalMode == JournalMode.WAL
                && this.walCheckpointIntervalSeconds > 0;
    }

    public boolean isPeriodicDefragEnabled() {
        return this.mode != Mode.IN_MEMORY && this.defragIntervalSeconds > 0;
    }

    private static Mode extractMode(final Map<String, Object> properties) {
        try {
            return Mode.valueOf(MODE_PROPERTY.get(properties));
        } catch (final Exception e) {
            logger.warn("failed to parse db mode, falling back to IN_MEMORY", e);
            return Mode.IN_MEMORY;
        }
    }

    private static JournalMode extractJournalMode(final Map<String, Object> properties) {
        try {
            return JournalMode.valueOf(JOURNAL_MODE_PROPERTY.get(properties));
        } catch (final Exception e) {
            logger.warn("failed to parse db journal mode, falling back to ROLLBACK_JOURNAL", e);
            return JournalMode.ROLLBACK_JOURNAL;
        }
    }

    public String getDbUrl() {
        if (mode == Mode.PERSISTED) {
            return "jdbc:sqlite:file:" + this.path;
        } else {
            return "jdbc:sqlite:file:" + kuraServicePid + "?mode=memory&cache=shared";
        }
    }

    private static EncryptionKeyFormat extractEncryptionKeyFormat(final Map<String, Object> properties) {
        try {
            return EncryptionKeyFormat.valueOf(ENCRYPTION_KEY_FORMAT_PROPERTY.get(properties));
        } catch (final Exception e) {
            return EncryptionKeyFormat.ASCII;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(defragIntervalSeconds, encryptionKey, encryptionKeyFormat, journalMode, kuraServicePid,
                maxConnectionPoolSize, mode, path, walCheckpointIntervalSeconds);
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (!(obj instanceof SqliteDbServiceOptions)) {
            return false;
        }
        SqliteDbServiceOptions other = (SqliteDbServiceOptions) obj;
        return defragIntervalSeconds == other.defragIntervalSeconds
                && Objects.equals(encryptionKey, other.encryptionKey)
                && encryptionKeyFormat == other.encryptionKeyFormat && journalMode == other.journalMode
                && Objects.equals(kuraServicePid, other.kuraServicePid)
                && maxConnectionPoolSize == other.maxConnectionPoolSize && mode == other.mode
                && Objects.equals(path, other.path)
                && walCheckpointIntervalSeconds == other.walCheckpointIntervalSeconds;
    }

}
