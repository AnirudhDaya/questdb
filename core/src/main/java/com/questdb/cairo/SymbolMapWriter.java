/*******************************************************************************
 *    ___                  _   ____  ____
 *   / _ \ _   _  ___  ___| |_|  _ \| __ )
 *  | | | | | | |/ _ \/ __| __| | | |  _ \
 *  | |_| | |_| |  __/\__ \ |_| |_| | |_) |
 *   \__\_\\__,_|\___||___/\__|____/|____/
 *
 * Copyright (C) 2014-2017 Appsicle
 *
 * This program is free software: you can redistribute it and/or  modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 *
 ******************************************************************************/

package com.questdb.cairo;

import com.questdb.common.SymbolTable;
import com.questdb.log.Log;
import com.questdb.log.LogFactory;
import com.questdb.std.*;
import com.questdb.std.str.Path;

import java.io.Closeable;

public class SymbolMapWriter implements Closeable {
    static final int HEADER_SIZE = 64;
    private static final Log LOG = LogFactory.getLog(SymbolMapWriter.class);

    private final BitmapIndexWriter indexWriter;
    private final ReadWriteMemory charMem;
    private final ReadWriteMemory offsetMem;
    private final CharSequenceLongHashMap cache;
    private final int maxHash;

    public SymbolMapWriter(CairoConfiguration configuration, Path path, CharSequence name, boolean useCache, long symbolCount) {
        final int plen = path.length();
        try {
            final long mapPageSize = configuration.getFilesFacade().getMapPageSize();

            // this constructor does not create index. Index must exist
            // and we use "offset" file to store "header"
            path.trimTo(plen).concat(name).put(".o").$();
            if (!configuration.getFilesFacade().exists(path)) {
                LOG.error().$(path).$(" is not found").$();
                throw CairoException.instance(0).put("SymbolMap does not exist: ").put(path);
            }

            // is there enough length in "offset" file for "header"?
            long len = configuration.getFilesFacade().length(path);
            if (len < HEADER_SIZE) {
                LOG.error().$(path).$(" is too short [len=").$(len).$(']').$();
                throw CairoException.instance(0).put("SymbolMap is too short: ").put(path);
            }

            // open "offset" memory and make sure we start appending from where
            // we left off. Where we left off is stored externally to symbol map
            this.offsetMem = new ReadWriteMemory(configuration.getFilesFacade(), path, mapPageSize);
            final int symbolCapacity = offsetMem.getInt(0);
            this.offsetMem.jumpTo(keyToOffset(symbolCount));

            // index writer is used to identify attempts to store duplicate symbol value
            this.indexWriter = new BitmapIndexWriter(configuration, path.trimTo(plen), name, 4);

            // this is the place where symbol values are stored
            this.charMem = new ReadWriteMemory(configuration.getFilesFacade(), path.trimTo(plen).concat(name).put(".c").$(), mapPageSize);

            // move append pointer for symbol values in the correct place
            jumpCharMemToSymbolCount(symbolCount);

            // we use index hash maximum equals to half of symbol capacity, which
            // theoretically should require 2 value cells in index per hash
            // we use 4 cells to compensate for occasionally unlucky hash distribution
            this.maxHash = Numbers.ceilPow2(symbolCapacity / 2) - 1;

            if (useCache) {
                this.cache = new CharSequenceLongHashMap(symbolCapacity);
            } else {
                this.cache = null;
            }
            LOG.info().$("open [name=").$(path.trimTo(plen).concat(name).$()).$(", fd=").$(this.offsetMem.getFd()).$(", cache=").$(cache != null).$(", capacity=").$(symbolCapacity).$(']').$();
        } catch (CairoException e) {
            close();
            throw e;
        } finally {
            path.trimTo(plen);
        }
    }

    public static void create(CairoConfiguration configuration, Path path, CharSequence name, int symbolCapacity) {
        int plen = path.length();
        try {
            try (ReadWriteMemory mem = new ReadWriteMemory(configuration.getFilesFacade(), path.concat(name).put(".o").$(), configuration.getFilesFacade().getMapPageSize())) {
                mem.putInt(symbolCapacity);
                mem.jumpTo(HEADER_SIZE);
            }

            configuration.getFilesFacade().touch(path.trimTo(plen).concat(name).put(".c").$());
            new BitmapIndexWriter(configuration, path.trimTo(plen), name, 4).close();
        } finally {
            path.trimTo(plen);
        }
    }

    @Override
    public void close() {
        Misc.free(indexWriter);
        Misc.free(charMem);
        if (this.offsetMem != null) {
            long fd = this.offsetMem.getFd();
            Misc.free(offsetMem);
            LOG.info().$("closed [fd=").$(fd).$(']').$();
        }
    }

    public long put(CharSequence symbol) {

        if (symbol == null) {
            return SymbolTable.VALUE_IS_NULL;
        }

        if (cache != null) {
            long result = cache.get(symbol);
            if (result != -1) {
                return result;
            }
            result = lookupAndPut(symbol);
            cache.put(symbol.toString(), result);
            return result;
        }
        return lookupAndPut(symbol);
    }

    public void rollback(long symbolCount) {
        indexWriter.rollbackValues(keyToOffset(symbolCount));
        offsetMem.jumpTo(keyToOffset(symbolCount));
        jumpCharMemToSymbolCount(symbolCount);
        if (cache != null) {
            cache.clear();
        }
    }

    static long offsetToKey(long offset) {
        return (offset - HEADER_SIZE) / 8;
    }

    static long keyToOffset(long key) {
        return HEADER_SIZE + key * 8;
    }

    private void jumpCharMemToSymbolCount(long symbolCount) {
        if (symbolCount > 0) {
            long lastSymbolOffset = this.offsetMem.getLong(keyToOffset(symbolCount - 1));
            int l = VirtualMemory.getStorageLength(this.charMem.getStr(lastSymbolOffset));
            this.charMem.jumpTo(lastSymbolOffset + l);
        } else {
            this.charMem.jumpTo(0);
        }
    }

    private long lookupAndPut(CharSequence symbol) {
        int hash = Hash.boundedHash(symbol, maxHash);
        BitmapIndexCursor cursor = indexWriter.getCursor(hash);
        while (cursor.hasNext()) {
            long offsetOffset = cursor.next();
            if (Chars.equals(symbol, charMem.getStr(offsetMem.getLong(offsetOffset)))) {
                return offsetToKey(offsetOffset);
            }
        }
        return put0(symbol, hash);
    }

    private long put0(CharSequence symbol, int hash) {
        long offsetOffset = offsetMem.getAppendOffset();
        offsetMem.putLong(charMem.putStr(symbol));
        indexWriter.add(hash, offsetOffset);
        return offsetToKey(offsetOffset);
    }
}
