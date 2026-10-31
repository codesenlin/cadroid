/*
 * QuickJS 的 Java 桥（JS 侧，E2 第一步）。
 *
 * 宿主通过 qjs.set("__caBridge", QuickJsBridge::class.java, host) 注入 __caBridge，
 * 桥协议是单方法 String → String（JSON）：
 *   请求: {op, ...}
 *   响应: {ok:true, v:{t:v}} 或 {ok:false, err, msg}
 *
 * 值标签: null/b/i/d/s/h(宿主对象 handle)/c(类名)/a(数组)
 *
 * 这一层负责在 JS 里模拟 Rhino 的 LiveConnect 基础能力：
 *   java / android / Packages 命名空间、Class.forName、静态/实例字段与方法、
 *   构造、instanceof、数组读写。
 *
 * 仍然不支持（E2 后续）：把 JS 函数/对象传给 Java（回调、接口实现）、
 *   Java 的 Proxy、泛型信息、Java 异常类型映射到 JS。
 */
(function () {
    "use strict";

    if (typeof __caCall !== "function") {
        throw new Error("QuickJS 桥未注入：__caCall 不存在");
    }

    var handleCache = {};

    // 脚本里用了 V8/Rhino 才有的 Error.captureStackTrace（Log.throwError），
    // QuickJS 没这个 API，给个保底实现（只保证不抛 TypeError，stack 用当前调用栈）
    if (typeof Error.captureStackTrace !== "function") {
        Error.captureStackTrace = function (err, ctor) {
            try {
                if (!err.stack) err.stack = (new Error()).stack || String(err);
            } catch (e) {
            }
        };
    }

    function rpcRaw(req) {
        var text = __caCall(JSON.stringify(req));
        var res;
        try {
            res = JSON.parse(text);
        } catch (e) {
            throw new Error("桥返回了非法 JSON: " + text);
        }
        return res;
    }

    function rawRpc(req, args) {
        var text = __caRaw(JSON.stringify(req), args);
        var res;
        try {
            res = JSON.parse(text);
        } catch (e) {
            throw new Error("桥返回了非法 JSON: " + text);
        }
        if (!res.ok) {
            var err = new Error(res.msg || res.err || "Java 调用失败");
            err.javaClass = res.err;
            throw err;
        }
        return res.v;
    }

    function rawCall(req, args) {
        return decode(rawRpc(req, args));
    }

    function rpc(req) {
        var res = rpcRaw(req);
        if (!res.ok) {
            var err = new Error(res.msg || res.err || "Java 调用失败");
            err.javaClass = res.err;
            throw err;
        }
        return res.v;
    }

    // ---------- 值编解码 ----------

    function encode(v) {
        if (v === null || v === undefined) return { t: "null" };
        if (typeof v === "boolean") return { t: "b", v: v };
        if (typeof v === "number") {
            return (v === (v | 0)) ? { t: "i", v: v } : { t: "d", v: v };
        }
        if (typeof v === "string") return { t: "s", v: v };
        if (v instanceof JavaObject) return { t: "h", v: v.__handle };
        if (v instanceof JavaClass) return { t: "c", v: v.__name };
        if (Array.isArray(v)) return { t: "a", v: v.map(encode) };
        if (typeof v === "function") {
            // 类代理（java.lang.String 这类）作为 Class 参数传
            if (typeof v.__name === "string") return { t: "c", v: v.__name };
            throw new Error("E2：不能把 JS 函数传给 Java");
        }
        throw new Error("E2：不能把 JS 对象传给 Java: " + Object.prototype.toString.call(v));
    }

    function decode(v) {
        if (!v) return null;
        switch (v.t) {
            case "null": return null;
            case "b": return !!v.v;
            case "i":
            case "d": return v.v;
            case "s": return v.v;
            case "c": return getClass(v.v);
            case "h": return wrapHandle(v.v, v.cls);
            case "a": {
                var arr = [];
                for (var i = 0; i < v.v.length; i++) arr.push(decode(v.v[i]));
                return arr;
            }
            default: throw new Error("未知值类型: " + v.t);
        }
    }

    // ---------- 宿主对象包装 ----------

    function JavaObject(handle, cls) {
        this.__handle = handle;
        this.__cls = cls;
    }

    function wrapHandle(handle, cls) {
        if (handleCache[handle]) return handleCache[handle];
        var obj = new JavaObject(handle, cls || "java.lang.Object");
        var proxy = new Proxy(obj, instanceHandler(handle));
        handleCache[handle] = proxy;
        return proxy;
    }

    function instanceHandler(handle) {
        return {
            get: function (t, prop) {
                // 只看自有属性：`in` 会把 Object.prototype 上的 valueOf/toString 也算进来
                if (Object.prototype.hasOwnProperty.call(t, prop)) return t[prop];
                // ToPrimitive：Java 的 Number/Boolean/String 包装要能当 JS 原始值用
                // （Rhino 的 NativeJavaObject.getDefaultValue 行为）
                if (prop === Symbol.toPrimitive) {
                    return function (hint) {
                        var p = rpcRaw({ op: "primitive", h: handle });
                        if (p.ok) return decode(p.v);
                        return hint === "string" ? String(t.__cls) : NaN;
                    };
                }
                var key = String(prop);
                // 方法闭包缓存：同一个方法反复调用时不再走 RPC 探测
                if (t.__m && Object.prototype.hasOwnProperty.call(t.__m, key)) return t.__m[key];
                // 影子属性：Java 端存的是 JS 对象（例如 view.tag = holder），
                // 取回来要还是原来那个 JS 对象，不能变成 JavaObject 包装
                if (t.__props && Object.prototype.hasOwnProperty.call(t.__props, key)) {
                    return t.__props[key];
                }
                if (key === "toJSON") return undefined;
                // 字段 → 值；方法 → 函数；都不存在 → undefined
                var res = rpcRaw({ op: "get", h: handle, n: key });
                if (res.ok) {
                    if (res.v.t === "method") {
                        var callReq = { op: "call", h: handle, n: key };
                        var fn = function () {
                            var args = Array.prototype.slice.call(arguments);
                            return rawCall(callReq, args);
                        };
                        if (!t.__m) t.__m = {};
                        t.__m[key] = fn;
                        return fn;
                    }
                    return decode(res.v);
                }
                return undefined;
            },
            set: function (t, prop, value) {
                var key = String(prop);
                if (key.charAt(0) === "_") { t[prop] = value; return true; }
                // JS 对象/函数留在 JS 侧做影子：Java 的 Object 字段存进去再取出来
                // 不能还原成同一个 JS 对象（Rhino 的 NativeJavaObject 可以）
                if (value !== null && (typeof value === "object" || typeof value === "function")) {
                    if (!t.__props) t.__props = {};
                    t.__props[key] = value;
                }
                // 用 raw 版本：字段类型可能是 Object，值可能是 JS 对象/函数
                __caRaw(JSON.stringify({ op: "put", h: handle, n: key }), [value]);
                return true;
            },
            has: function (t, prop) {
                if (Object.prototype.hasOwnProperty.call(t, prop)) return true;
                var res = rpcRaw({ op: "get", h: handle, n: String(prop) });
                return res.ok;
            }
        };
    }

    // ---------- class 包装 ----------

    function JavaClass(name) {
        this.__name = name;
    }

    var classCache = {};
    var classInfoCache = {};

    function getClassInfo(name) {
        if (classInfoCache[name]) return classInfoCache[name];
        var res = rpcRaw({ op: "classInfo", c: name });
        var info = (res.ok && res.v && res.v.info) ? res.v.info : { interface: false };
        classInfoCache[name] = info;
        return info;
    }

    function classHandler(name) {
        return {
            get: function (t, prop) {
                // 只看自有属性：`in` 会把 Object.prototype 上的 valueOf/toString 也算进来
                if (Object.prototype.hasOwnProperty.call(t, prop)) return t[prop];
                // ToPrimitive：Java 的 Number/Boolean/String 包装要能当 JS 原始值用
                // （Rhino 的 NativeJavaObject.getDefaultValue 行为）
                if (prop === Symbol.toPrimitive) {
                    return function (hint) {
                        var p = rpcRaw({ op: "primitive", h: handle });
                        if (p.ok) return decode(p.v);
                        return hint === "string" ? String(t.__cls) : NaN;
                    };
                }
                var key = String(prop);
                // 影子属性：Java 端存的是 JS 对象（例如 view.tag = holder），
                // 取回来要还是原来那个 JS 对象，不能变成 JavaObject 包装
                if (t.__props && Object.prototype.hasOwnProperty.call(t.__props, key)) {
                    return t.__props[key];
                }
                if (key === "toJSON") return undefined;
                if (key === "class") return decode(rpc({ op: "classOf", c: name }));
                if (name === "org.mozilla.javascript.Context" && key === "getCurrentContext") {
                    return function () { return rhinoContextShim; };
                }
                if (key === "getName") return function () { return name; };
                // 静态方法缓存（闭包 + 预编码请求）
                if (t.__sm && Object.prototype.hasOwnProperty.call(t.__sm, key)) return t.__sm[key];
                // 静态字段 → 值；静态方法 → 函数；都没有 → undefined
                var res = rpcRaw({ op: "getStatic", c: name, n: key });
                if (res.ok) {
                    if (res.v.t === "method") {
                        var scReq = { op: "call", c: name, n: key };
                        var sfn = function () {
                            var args = Array.prototype.slice.call(arguments);
                            return rawCall(scReq, args);
                        };
                        if (!t.__sm) t.__sm = {};
                        t.__sm[key] = sfn;
                        return sfn;
                    }
                    return decode(res.v);
                }
                // 嵌套类：android.os.Build.VERSION → android.os.Build$VERSION
                var nested = name + "$" + key;
                var nestedRes = rpcRaw({ op: "classExists", c: nested });
                if (nestedRes.ok && nestedRes.v && nestedRes.v.v === true) {
                    return getClass(nested);
                }
                // Class 实例方法：getConstructor / getMethod / getSuperclass / isPrimitive ...
                try {
                    if (!t.__refH) {
                        t.__refH = rpc({ op: "classRef", c: name }).v;
                    }
                    if (t.__refH) {
                        var ires = rpc({ op: "get", h: t.__refH, n: key });
                        if (ires && ires.t === "method") {
                            var refH = t.__refH;
                            return function () {
                                var args = Array.prototype.slice.call(arguments);
                                return rawCall({ op: "call", h: refH, n: key }, args);
                            };
                        }
                        return decode(ires);
                    }
                } catch (e) {
                    rpcRaw({ op: "log", m: "classGet 失败 " + name + "." + key + " (" + e + ")" });
                }
                return undefined;
                // 旧逻辑（保留在下面，不再使用）
                return function () {
                    var args = Array.prototype.slice.call(arguments);
                    var callRes = rpcRaw({
                        op: "call", c: name, n: key, a: args.map(encode)
                    });
                    if (callRes.ok) return decode(callRes.v);
                    // 静态方法不存在时，回退到「把类名当成 Class 对象」调实例方法
                    // （例如 java.lang.String.class.getDeclaredMethod(...)）
                    if (callRes.err === "java.lang.NoSuchMethodException") {
                        var clsVal = rpcRaw({ op: "classOf", c: name });
                        if (clsVal.ok) {
                            return decode(rpc({
                                op: "call", h: clsVal.v.v, n: key, a: args.map(encode)
                            }));
                        }
                    }
                    throw new Error(callRes.msg || ("Java 方法不存在: " + name + "." + key));
                };
            },
            apply: function (t, thisArg, args) {
                return constructClass(name, args);
            },
            construct: function (t, args) {
                return constructClass(name, args);
            },
            has: function () { return true; }
        };
    }


    function constructClass(name, args) {
        // Rhino 的 Scriptable 兼容对象（L.js / DBSettings.js）：
        // 不创建 Java 适配器，直接造一个 JS Proxy 当动态命名空间，
        // 避免属性访问绕回 Java（cx.toObject 垫片原样返回）。
        if (name === "org.mozilla.javascript.Scriptable") {
            var impl = args[0] || {};
            var scriptable = new Proxy({}, {
                get: function (target, prop) {
                    var fn = impl.get;
                    if (typeof fn !== "function") return undefined;
                    return fn.call(impl, String(prop), scriptable);
                },
                set: function (target, prop, value) {
                    if (typeof impl.put === "function") {
                        impl.put(String(prop), scriptable, value);
                    }
                    return true;
                },
                has: function (target, prop) {
                    if (typeof impl.has === "function") return !!impl.has(String(prop), scriptable);
                    return false;
                },
                deleteProperty: function (target, prop) {
                    if (typeof impl.delete === "function") impl.delete(String(prop));
                    return true;
                }
            });
            return scriptable;
        }
        // 接口：Rhino 允许 new SomeInterface({ 方法: function(){} })
        // 也允许 new java.lang.Runnable(function(){...})
        if (getClassInfo(name).interface) {
            var impl = args[0];
            if (impl === null || impl === undefined) return null;
            // 函数实现：直接用函数本身，Java 需要适配器时由参数转换层生成
            if (typeof impl === "function") return impl;
            // JS 对象实现：返回直接代理真实 JS 实现的包装，方法调用在 JS 里完成。
            // 不绕 Java 的原因：Rhino 的 this 就是原 JS 对象，返回的 JS 对象也还是
            // 同一个对象（绕 Java 会把返回值变成 JavaObject/甚至 null）。
            var ifaceProxy = new Proxy({}, {
                get: function (t, prop) {
                    if (Object.prototype.hasOwnProperty.call(t, prop)) return t[prop];
                    var key = String(prop);
                    var v = impl[key];
                    if (typeof v === "function") return v.bind(impl);
                    return v;
                },
                set: function (t, prop, value) {
                    impl[String(prop)] = value;
                    return true;
                },
                has: function (t, prop) { return prop in impl; }
            });
            ifaceProxy.__jsInterface = name;
            ifaceProxy.__impl = impl;
            return ifaceProxy;
        }
        return rawCall({ op: "new", c: name }, args);

    }
    function getClass(name) {
        if (classCache[name]) return classCache[name];
        // target 用普通函数：Proxy 的 target 必须可构造，`new java.util.ArrayList()` 才有效
        var base = function () {};
        base.__name = name;
        // instanceof：脚本里写 `x instanceof java.lang.CharSequence`
        Object.defineProperty(base, Symbol.hasInstance, {
            value: function (x) { return instanceOf(x, name); }
        });
        var proxy = new Proxy(base, classHandler(name));
        classCache[name] = proxy;
        return proxy;
    }

    function instanceOf(x, name) {
        if (x instanceof JavaObject) {
            var res = rpcRaw({ op: "isInstance", h: x.__handle, c: name });
            return res.ok && res.v.t === "b" && res.v.v;
        }
        if (typeof x === "string") {
            return name === "java.lang.String" || name === "java.lang.CharSequence" ||
                name === "java.lang.Object" || name === "java.io.Serializable";
        }
        if (typeof x === "number") {
            return name === "java.lang.Number" || name === "java.lang.Object" ||
                name === "java.lang.Double" || name === "java.lang.Integer";
        }
        if (typeof x === "boolean") {
            return name === "java.lang.Boolean" || name === "java.lang.Object";
        }
        return false;
    }

    // ---------- 包命名空间 ----------

    var packageCache = {};

    function packageProxy(prefix) {
        if (packageCache[prefix]) return packageCache[prefix];
        var target = {};
        var proxy = new Proxy(target, {
            get: function (t, prop) {
                if (Object.prototype.hasOwnProperty.call(t, prop)) return t[prop];
                var name = prefix ? prefix + "." + String(prop) : String(prop);
                var res = rpcRaw({ op: "classExists", c: name });
                var value = (res.ok && res.v && res.v.v === true) ? getClass(name) : packageProxy(name);
                // 缓存解析结果：脚本里 java.lang.System 这种访问是高频操作
                t[prop] = value;
                return value;
            },
            has: function () { return true; }
        });
        packageCache[prefix] = proxy;
        return proxy;
    }

    var java = packageProxy("java");
    var android = packageProxy("android");

    globalThis.java = java;
    globalThis.android = android;
    var namedPackages = {
        java: java,
        android: android,
        javax: packageProxy("javax"),
        org: packageProxy("org"),
        com: packageProxy("com"),
        edu: packageProxy("edu")
    };
    globalThis.Packages = new Proxy(namedPackages, {
        get: function (t, prop) {
            var key = String(prop);
            if (Object.prototype.hasOwnProperty.call(t, key)) return t[key];
            // Rhino 支持 Packages["[B"]、Packages["java.lang.String"] 这种直接写类名
            var clsRes = rpcRaw({ op: "classExists", c: key });
            if (clsRes.ok && clsRes.v && clsRes.v.v === true) return getClass(key);
            return packageProxy(key);
        },
        has: function (t, prop) {
            return Object.prototype.hasOwnProperty.call(t, String(prop)) || true;
        }
    });
    // Rhino 默认也把这些顶层命名空间挂在全局
    globalThis.org = globalThis.Packages.org;
    globalThis.com = globalThis.Packages.com;
    globalThis.javax = globalThis.Packages.javax;
    globalThis.edu = globalThis.Packages.edu;

    // ---------- Rhino 兼容垫片 ----------
    // 脚本里有几处直接用了 Rhino 的 API（L.js / DBSettings.js / DexPlugin.js）：
    //   cx.getWrapFactory().wrapJavaClass(...) / cx.toObject(...) / cx.getApplicationClassLoader()
    // QuickJS 下给一个等价 shim，避免它们拿到 null。
    var rhinoContextShim = {
        getWrapFactory: function () {
            return {
                wrapJavaClass: function (cx, scope, clazz) {
                    var name = clazz && clazz.getName ? clazz.getName() : String(clazz);
                    return getClass(name);
                }
            };
        },
        toObject: function (obj) { return obj; },
        getApplicationClassLoader: function () {
            return ScriptInterface.getContext().getClassLoader();
        }
    };

    // ---------- ScriptInterface ----------

    var init = rpcRaw({ op: "init" });
    if (!init.ok) throw new Error("QuickJS 桥 init 失败: " + (init.msg || ""));
    globalThis.ScriptInterface = wrapHandle(init.handle, "com.xero.ca.ScriptInterface");

    // ---------- 给 Java 侧调 JS 函数用 ----------

    globalThis.__caCallAdapter = function (fn, argsJson) {
        var args = JSON.parse(argsJson).map(decode);
        return fn.apply(null, args);
    };

    globalThis.__caCallHook = function (name, argsJson) {
        var f = globalThis[name];
        if (typeof f !== "function") return undefined;
        var args = JSON.parse(argsJson);
        return f.apply(null, args);
    };
})();
