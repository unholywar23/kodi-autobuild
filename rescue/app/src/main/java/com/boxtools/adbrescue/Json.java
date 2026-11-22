package com.boxtools.adbrescue;

import java.util.List;
import java.util.Map;

/** Minimal JSON writer. No third-party dependencies. */
public final class Json {
    private Json() {}

    public static String str(String s) {
        if (s == null) return "null";
        StringBuilder b = new StringBuilder(s.length() + 2);
        b.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n"); break;
                case '\r': b.append("\\r"); break;
                case '\t': b.append("\\t"); break;
                case '\b': b.append("\\b"); break;
                case '\f': b.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        b.append(String.format("\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
            }
        }
        b.append('"');
        return b.toString();
    }

    /** Serialize a value: String, Number, Boolean, null, List, Map. */
    @SuppressWarnings("unchecked")
    public static String val(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return str((String) v);
        if (v instanceof Boolean || v instanceof Number) return v.toString();
        if (v instanceof Map) {
            StringBuilder b = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<String, Object> e : ((Map<String, Object>) v).entrySet()) {
                if (!first) b.append(',');
                first = false;
                b.append(str(e.getKey())).append(':').append(val(e.getValue()));
            }
            return b.append('}').toString();
        }
        if (v instanceof List) {
            StringBuilder b = new StringBuilder("[");
            boolean first = true;
            for (Object o : (List<Object>) v) {
                if (!first) b.append(',');
                first = false;
                b.append(val(o));
            }
            return b.append(']').toString();
        }
        return str(v.toString());
    }
}
