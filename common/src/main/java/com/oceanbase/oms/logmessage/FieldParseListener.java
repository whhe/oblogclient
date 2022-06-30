/* Copyright (c) 2021 OceanBase and/or its affiliates. All rights reserved.
oblogclient is licensed under Mulan PSL v2.
You can use this software according to the terms and conditions of the Mulan PSL v2.
You may obtain a copy of Mulan PSL v2 at:
         http://license.coscl.org.cn/MulanPSL2
THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
See the Mulan PSL v2 for more details. */

package com.oceanbase.oms.logmessage;

/** This interface defined a kind of listener for field parsing. */
public interface FieldParseListener {

    /**
     * Handle the filed parsing result.
     *
     * @param fieldName
     * @param type
     * @param encoding
     * @param value
     * @param notNUll
     * @param isPrev
     */
    void parseNotify(
            String fieldName,
            int type,
            String encoding,
            ByteString value,
            boolean notNUll,
            boolean isPrev);

    // only support value, cause we already know schema info
    void parseNotify(int type, ByteString value, String encoding, boolean isPrev);

    // hint to identify if schema info include fieldName, type, encoding, not null is needed
    boolean needSchemaInfo();
}
