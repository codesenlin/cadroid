package com.xero.ca.script;

import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;

public class WrappedUndefined {
    public static Scriptable get(Context cx, Scriptable scope) {
        Object undefined = Context.getUndefinedValue();
        return cx.getWrapFactory().wrapAsJavaObject(cx, scope, undefined, null);
    }
}
