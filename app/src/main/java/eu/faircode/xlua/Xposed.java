/*
    This file is part of XPrivacyLua.

    XPrivacyLua is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    XPrivacyLua is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with XPrivacyLua.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2017-2018 Marcel Bokhorst (M66B)
 */

package eu.faircode.xlua;

import android.app.Application;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;

import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaClosure;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Prototype;
import org.luaj.vm2.Varargs;
import org.luaj.vm2.compiler.LuaC;
import org.luaj.vm2.lib.DebugLib;
import org.luaj.vm2.lib.OneArgFunction;
import org.luaj.vm2.lib.TwoArgFunction;
import org.luaj.vm2.lib.VarArgFunction;
import org.luaj.vm2.lib.jse.CoerceJavaToLua;
import org.luaj.vm2.lib.jse.JsePlatform;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.IXposedHookZygoteInit;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Xposed implements IXposedHookZygoteInit, IXposedHookLoadPackage {
    private static final String TAG = "XLua.Xposed";

    private static int version = -1;

    public void initZygote(final IXposedHookZygoteInit.StartupParam startupParam) throws Throwable {
        Log.i(TAG, "initZygote system=" + startupParam.startsSystemServer + " debug=" + BuildConfig.DEBUG);
    }

    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        int uid = Process.myUid();
        String self = Xposed.class.getPackage().getName();
        Log.i(TAG, "Loaded " + lpparam.packageName + ":" + uid);

        if ("android".equals(lpparam.packageName))
            hookAndroid(lpparam);

        if ("com.android.providers.settings".equals(lpparam.packageName))
            hookSettings(lpparam);

        if (!"android".equals(lpparam.packageName) &&
                !self.equals(lpparam.packageName) && !Util.PRO_PACKAGE_NAME.equals(lpparam.packageName))
            hookApplication(lpparam);
    }

    private void hookAndroid(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/master/services/core/java/com/android/server/am/ActivityManagerService.java
        Class<?> clsAM = Class.forName("com.android.server.am.ActivityManagerService", false, lpparam.classLoader);
        XposedBridge.hookAllMethods(clsAM, "systemReady", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    Log.i(TAG, "System ready");

                    // Search for context
                    Context context = null;
                    Class<?> cAm = param.thisObject.getClass();
                    while (cAm != null && context == null) {
                        for (Field field : cAm.getDeclaredFields())
                            if (field.getType().equals(Context.class)) {
                                field.setAccessible(true);
                                context = (Context) field.get(param.thisObject);
                                Log.i(TAG, "Context found in " + cAm + " as " + field.getName());
                                break;
                            }
                        cAm = cAm.getSuperclass();
                    }
                    if (context == null)
                        throw new Throwable("Context not found");

                    // public static UserManagerService getInstance()
                    Class<?> clsUM = Class.forName("com.android.server.pm.UserManagerService", false, param.thisObject.getClass().getClassLoader());
                    Object um = clsUM.getDeclaredMethod("getInstance").invoke(null);

                    //  public int[] getUserIds()
                    int[] userids = (int[]) um.getClass().getDeclaredMethod("getUserIds").invoke(um);


                    // Listen for package changes
                    for (int userid : userids) {
                        Log.i(TAG, "Registering package listener user=" + userid);
                        IntentFilter ifPackageAdd = new IntentFilter();
                        ifPackageAdd.addAction(Intent.ACTION_PACKAGE_ADDED);
                        ifPackageAdd.addAction(Intent.ACTION_PACKAGE_FULLY_REMOVED);
                        ifPackageAdd.addDataScheme("package");
                        Util.createContextForUser(context, userid).registerReceiver(new ReceiverPackage(), ifPackageAdd);
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    XposedBridge.log(ex);
                }
            }
        });
    }

    private void hookSettings(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        // https://android.googlesource.com/platform/frameworks/base/+/master/packages/SettingsProvider/src/com/android/providers/settings/SettingsProvider.java
        Class<?> clsSet = Class.forName("com.android.providers.settings.SettingsProvider", false, lpparam.classLoader);

        // Bundle call(String method, String arg, Bundle extras)
        Method mCall = clsSet.getMethod("call", String.class, String.class, Bundle.class);
        XposedBridge.hookMethod(mCall, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    String method = (String) param.args[0];
                    String arg = (String) param.args[1];
                    Bundle extras = (Bundle) param.args[2];

                    if ("xlua".equals(method))
                        if ("getVersion".equals(arg)) {
                            Bundle result = new Bundle();
                            result.putInt("version", version);
                            param.setResult(result);
                        } else
                            try {
                                Method mGetContext = param.thisObject.getClass().getMethod("getContext");
                                Context context = (Context) mGetContext.invoke(param.thisObject);
                                getModuleVersion(context);
                                param.setResult(XProvider.call(context, arg, extras));
                            } catch (IllegalArgumentException ex) {
                                Log.i(TAG, "Error: " + ex.getMessage());
                                param.setThrowable(ex);
                            } catch (Throwable ex) {
                                Log.e(TAG, Log.getStackTraceString(ex));
                                XposedBridge.log(ex);
                                param.setResult(null);
                            }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    XposedBridge.log(ex);
                }
            }
        });

        // Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder)
        Method mQuery = clsSet.getMethod("query", Uri.class, String[].class, String.class, String[].class, String.class);
        XposedBridge.hookMethod(mQuery, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    String[] projection = (String[]) param.args[1];
                    String[] selection = (String[]) param.args[3];
                    if (projection != null && projection.length > 0 &&
                            projection[0] != null && projection[0].startsWith("xlua.")) {
                        try {
                            Method mGetContext = param.thisObject.getClass().getMethod("getContext");
                            Context context = (Context) mGetContext.invoke(param.thisObject);
                            getModuleVersion(context);
                            param.setResult(XProvider.query(context, projection[0].split("\\.")[1], selection));
                        } catch (Throwable ex) {
                            Log.e(TAG, Log.getStackTraceString(ex));
                            XposedBridge.log(ex);
                            param.setResult(null);
                        }
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    XposedBridge.log(ex);
                }
            }
        });
    }

    private void hookApplication(final XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        final int uid = Process.myUid();
        Class<?> at = Class.forName("android.app.LoadedApk", false, lpparam.classLoader);
        XposedBridge.hookAllMethods(at, "makeApplication", new XC_MethodHook() {
            private boolean made = false;
            private Timer timer = null;
            private final Map<String, Map<String, Bundle>> queue = new HashMap<>();

            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                try {
                    if (!made) {
                        made = true;
                        Application app = (Application) param.getResult();
                        ContentResolver resolver = app.getContentResolver();

                        int userid = Util.getUserId(uid);
                        int start = Util.getUserUid(userid, 99000);
                        int end = Util.getUserUid(userid, 99999);
                        boolean isolated = (uid >= start && uid <= end);

                        if (isolated) {
                            Log.i(TAG, "Skipping isolated " + lpparam.packageName + ":" + uid);
                            return;
                        }

                        // Get hooks
                        List<XHook> hooks = new ArrayList<>();
                        Cursor hcursor = null;
                        try {
                            hcursor = resolver
                                    .query(XProvider.URI, new String[]{"xlua.getAssignedHooks"},
                                            null, new String[]{lpparam.packageName, Integer.toString(uid)},
                                            null);
                            while (hcursor != null && hcursor.moveToNext())
                                hooks.add(XHook.fromJSON(hcursor.getString(0)));
                        } finally {
                            if (hcursor != null)
                                hcursor.close();
                        }

                        Map<String, String> settings = new HashMap<>();

                        // Get global settings
                        Cursor scursor1 = null;
                        try {
                            scursor1 = resolver
                                    .query(XProvider.URI, new String[]{"xlua.getSettings"},
                                            null, new String[]{"global", Integer.toString(uid)},
                                            null);
                            while (scursor1 != null && scursor1.moveToNext())
                                settings.put(scursor1.getString(0), scursor1.getString(1));
                        } finally {
                            if (scursor1 != null)
                                scursor1.close();
                        }

                        // Get package settings
                        Cursor scursor2 = null;
                        try {
                            scursor2 = resolver
                                    .query(XProvider.URI, new String[]{"xlua.getSettings"},
                                            null, new String[]{lpparam.packageName, Integer.toString(uid)},
                                            null);
                            while (scursor2 != null && scursor2.moveToNext())
                                settings.put(scursor2.getString(0), scursor2.getString(1));
                        } finally {
                            if (scursor2 != null)
                                scursor2.close();
                        }

                        hookPackage(app, lpparam, uid, hooks, settings);
                    }
                } catch (Throwable ex) {
                    Log.e(TAG, Log.getStackTraceString(ex));
                    XposedBridge.log(ex);
                }
            }

            private void hookPackage(
                    final Context context,
                    final XC_LoadPackage.LoadPackageParam lpparam, final int uid,
                    List<XHook> hooks, final Map<String, String> settings) {

                for (final XHook hook : hooks)
                    try {
                        long install = SystemClock.elapsedRealtime();

                        // Compile script
                        InputStream is = new ByteArrayInputStream(hook.getLuaScript().getBytes());
                        final Prototype compiledScript = LuaC.instance.compile(is, "script");

                        // Get class
                        Class<?> cls;
                        try {
                            cls = Class.forName(hook.getResolvedClassName(), false, lpparam.classLoader);
                        } catch (ClassNotFoundException ex) {
                            if (hook.isOptional()) {
                                Log.i(TAG, "Optional hook=" + hook.getId() + ": " + ex);
                                continue;
                            } else
                                throw ex;
                        }

                        String[] m = hook.getMethodName().split(":");
                        if (m.length > 1) {
                            Field field = cls.getField(m[0]);
                            Object obj = field.get(null);
                            cls = obj.getClass();
                        }
                        String methodName = m[m.length - 1];

                        // Get parameter types
                        String[] p = hook.getParameterTypes();
                        final Class<?>[] paramTypes = new Class[p.length];
                        for (int i = 0; i < p.length; i++)
                            paramTypes[i] = resolveClass(p[i], lpparam.classLoader);

                        // Get return type
                        final Class<?> returnType = (hook.getReturnType() == null ? null :
                                resolveClass(hook.getReturnType(), lpparam.classLoader));

                        if (methodName.startsWith("#")) {
                            // Get field
                            Field field;
                            try {
                                field = resolveField(cls, methodName.substring(1), returnType);
                                field.setAccessible(true);
                            } catch (NoSuchFieldException ex) {
                                if (hook.isOptional()) {
                                    Log.i(TAG, "Optional hook=" + hook.getId() + ": " + ex.getMessage());
                                    continue;
                                } else
                                    throw ex;
                            }

                            // Initialize Lua runtime
                            Globals globals = getGlobals(lpparam, uid, hook);
                            LuaClosure closure = new LuaClosure(compiledScript, globals);
                            closure.call();

                            // Check if function exists
                            LuaValue func = globals.get("after");
                            if (func.isnil())
                                return;

                            // Run function
                            Varargs result = func.invoke(
                                    CoerceJavaToLua.coerce(hook),
                                    CoerceJavaToLua.coerce(new XParam(
                                            lpparam.packageName, uid,
                                            field,
                                            paramTypes, returnType, lpparam.classLoader,
                                            settings))
                            );

                            // Report use
                            boolean restricted = result.arg1().checkboolean();
                            if (restricted && hook.doUsage()) {
                                Bundle data = new Bundle();
                                data.putString("function", "after");
                                data.putInt("restricted", restricted ? 1 : 0);
                                report(context, hook.getId(), lpparam.packageName, uid, "use", data);
                            }
                        } else {
                            // Get method
                            final Method method;
                            try {
                                method = resolveMethod(cls, methodName, paramTypes);
                            } catch (NoSuchMethodException ex) {
                                if (hook.isOptional()) {
                                    Log.i(TAG, "Optional hook=" + hook.getId() + ": " + ex.getMessage());
                                    continue;
                                } else
                                    throw ex;
                            }

                            // Check return type
                            if (returnType != null && !method.getReturnType().equals(returnType))
                                throw new Throwable("Invalid return type got " + method.getReturnType() + " expected " + returnType);

                            // Hook method
                            XposedBridge.hookMethod(method, new XC_MethodHook() {
                                @Override
                                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                    execute(param, "before");
                                }

                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    execute(param, "after");
                                }

                                // Execute hook
                                private void execute(MethodHookParam param, String function) {
                                    try {
                                        long run = SystemClock.elapsedRealtime();

                                        // Initialize Lua runtime
                                        Globals globals = getGlobals(lpparam, uid, hook);
                                        LuaClosure closure = new LuaClosure(compiledScript, globals);
                                        closure.call();

                                        // Check if function exists
                                        LuaValue func = globals.get(function);
                                        if (func.isnil())
                                            return;

                                        // Run function
                                        Varargs result = func.invoke(
                                                CoerceJavaToLua.coerce(hook),
                                                CoerceJavaToLua.coerce(new XParam(
                                                        lpparam.packageName, uid,
                                                        param,
                                                        method.getParameterTypes(), method.getReturnType(),
                                                        lpparam.classLoader,
                                                        settings))
                                        );

                                        // Report use
                                        boolean restricted = result.arg1().checkboolean();
                                        if (restricted) {
                                            Bundle data = new Bundle();
                                            data.putString("function", function);
                                            data.putInt("restricted", restricted ? 1 : 0);
                                            data.putLong("duration", SystemClock.elapsedRealtime() - run);
                                            report(context, hook.getId(), lpparam.packageName, uid, "use", data);
                                        }
                                    } catch (Throwable ex) {
                                        Log.e(TAG, Log.getStackTraceString(ex));

                                        // Report use error
                                        Bundle data = new Bundle();
                                        data.putString("function", function);
                                        data.putString("exception", ex instanceof LuaError ? ex.getMessage() : Log.getStackTraceString(ex));
                                        report(context, hook.getId(), lpparam.packageName, uid, "use", data);
                                    }
                                }
                            });
                        }

                        // Report install
                        if (BuildConfig.DEBUG) {
                            Bundle data = new Bundle();
                            data.putLong("duration", SystemClock.elapsedRealtime() - install);
                            report(context, hook.getId(), lpparam.packageName, uid, "install", data);
                        }
                    } catch (Throwable ex) {
                        Log.e(TAG, Log.getStackTraceString(ex));

                        // Report install error
                        Bundle data = new Bundle();
                        data.putString("exception", ex instanceof LuaError ? ex.getMessage() : Log.getStackTraceString(ex));
                        report(context, hook.getId(), lpparam.packageName, uid, "install", data);
                    }
            }

            private void report(final Context context, String hook, final String packageName, final int uid, String event, Bundle data) {
                Bundle args = new Bundle();
                args.putString("hook", hook);
                args.putString("packageName", packageName);
                args.putInt("uid", uid);
                args.putString("event", event);
                args.putLong("time", new Date().getTime());
                args.putBundle("data", data);

                synchronized (queue) {
                    if (!queue.containsKey(event))
                        queue.put(event, new HashMap<String, Bundle>());
                    queue.get(event).put(hook, args);

                    if (timer == null) {
                        timer = new Timer();
                        timer.schedule(new TimerTask() {
                            public void run() {
                                Log.i(TAG, "Processing event queue package=" + packageName + ":" + uid);

                                List<Bundle> work = new ArrayList<>();
                                synchronized (queue) {
                                    for (String event : queue.keySet())
                                        for (String hook : queue.get(event).keySet())
                                            work.add(queue.get(event).get(hook));
                                    queue.clear();
                                    timer = null;
                                }

                                for (Bundle args : work)
                                    context.getContentResolver()
                                            .call(XProvider.URI, "xlua", "report", args);
                            }
                        }, 1000);
                    }
                }
            }
        });
    }

    private static void getModuleVersion(Context context) throws PackageManager.NameNotFoundException {
        if (version < 0) {
            String self = Xposed.class.getPackage().getName();
            PackageInfo pi = context.getPackageManager().getPackageInfo(self, 0);
            version = pi.versionCode;
            Log.i(TAG, "Loaded module version " + version);
        }
    }

    private static Class<?> resolveClass(String name, ClassLoader loader) throws ClassNotFoundException {
        if ("boolean".equals(name))
            return boolean.class;
        else if ("byte".equals(name))
            return byte.class;
        else if ("char".equals(name))
            return char.class;
        else if ("short".equals(name))
            return short.class;
        else if ("int".equals(name))
            return int.class;
        else if ("long".equals(name))
            return long.class;
        else if ("float".equals(name))
            return float.class;
        else if ("double".equals(name))
            return double.class;

        else if ("boolean[]".equals(name))
            return boolean[].class;
        else if ("byte[]".equals(name))
            return byte[].class;
        else if ("char[]".equals(name))
            return char[].class;
        else if ("short[]".equals(name))
            return short[].class;
        else if ("int[]".equals(name))
            return int[].class;
        else if ("long[]".equals(name))
            return long[].class;
        else if ("float[]".equals(name))
            return float[].class;
        else if ("double[]".equals(name))
            return double[].class;

        else if ("void".equals(name))
            return Void.TYPE;

        else
            return Class.forName(name, false, loader);
    }

    private static Field resolveField(Class<?> cls, String name, Class<?> type) throws NoSuchFieldException {
        try {
            Class<?> c = cls;
            while (c != null && !c.equals(Object.class))
                try {
                    Field field = c.getDeclaredField(name);
                    if (!field.getType().equals(type))
                        throw new NoSuchFieldException();
                    return field;
                } catch (NoSuchFieldException ex) {
                    for (Field field : c.getDeclaredFields()) {
                        if (!name.equals(field.getName()))
                            continue;

                        if (!field.getType().equals(type))
                            continue;

                        Log.i(TAG, "Resolved field=" + field);
                        return field;
                    }
                }
            throw new NoSuchFieldException(name);
        } catch (NoSuchFieldException ex) {
            Class<?> c = cls;
            while (c != null && !c.equals(Object.class)) {
                Log.i(TAG, c.toString());
                for (Method method : c.getDeclaredMethods())
                    Log.i(TAG, "- " + method.toString());
                c = c.getSuperclass();
            }
            throw ex;
        }
    }

    private static Method resolveMethod(Class<?> cls, String name, Class<?>[] params) throws NoSuchMethodException {
        try {
            Class<?> c = cls;
            while (c != null && !c.equals(Object.class))
                try {
                    return c.getDeclaredMethod(name, params);
                } catch (NoSuchMethodException ex) {
                    for (Method method : c.getDeclaredMethods()) {
                        if (!name.equals(method.getName()))
                            continue;

                        Class<?>[] mparams = method.getParameterTypes();

                        if (mparams.length != params.length)
                            continue;

                        boolean same = true;
                        for (int i = 0; i < mparams.length; i++) {
                            if (!params[i].isAssignableFrom(mparams[i])) {
                                same = false;
                                break;
                            }
                        }
                        if (!same)
                            continue;

                        Log.i(TAG, "Resolved method=" + method);
                        return method;
                    }
                    c = c.getSuperclass();
                    if (c == null)
                        throw ex;
                }
            throw new NoSuchMethodException(name);
        } catch (NoSuchMethodException ex) {
            Class<?> c = cls;
            while (c != null && !c.equals(Object.class)) {
                Log.i(TAG, c.toString());
                for (Method method : c.getDeclaredMethods())
                    Log.i(TAG, "- " + method.toString());
                c = c.getSuperclass();
            }
            throw ex;
        }
    }

    private static Globals getGlobals(XC_LoadPackage.LoadPackageParam lpparam, int uid, XHook hook) {
        Globals globals = JsePlatform.standardGlobals();

        if (BuildConfig.DEBUG)
            globals.load(new DebugLib());

        globals.set("log", new LuaLog(lpparam.packageName, uid, hook.getId()));
        globals.set("getPrivateField", new LuaGetPrivateField());
        globals.set("invokePrivateMethod", new LuaInvokePrivateMethod());

        return globals;
    }

    private static class LuaLog extends OneArgFunction {
        private final String packageName;
        private final int uid;
        private final String hook;

        LuaLog(String packageName, int uid, String hook) {
            this.packageName = packageName;
            this.uid = uid;
            this.hook = hook;
        }

        @Override
        public LuaValue call(LuaValue arg) {
            if (BuildConfig.DEBUG)
                Log.i(TAG, "Log " + packageName + ":" + uid + " " + hook + " " +
                        arg.toString() + " (" + arg.typename() + ")");
            return LuaValue.NIL;
        }
    }

    private static class LuaGetPrivateField extends TwoArgFunction {
        @Override
        public LuaValue call(LuaValue lobject, LuaValue jname) {
            try {
                Object object = lobject.touserdata();
                String name = jname.checkjstring();
                Field field = object.getClass().getDeclaredField(name);
                field.setAccessible(true);
                Object result = field.get(object);
                Log.i(TAG, "getPrivateField(" + name + ")=" + result);
                // TODO: result LuaValue's
                return LuaValue.userdataOf(result);
            } catch (Throwable ex) {
                Log.e(TAG, Log.getStackTraceString(ex));
                return LuaValue.NIL;
            }
        }
    }

    private static class LuaInvokePrivateMethod extends VarArgFunction {
        @Override
        public Varargs invoke(Varargs args) {
            try {
                Object object = args.touserdata(1);
                String name = args.tojstring(2);
                Object[] params = new Object[args.narg() - 2];
                Class<?>[] types = new Class<?>[args.narg() - 2];
                for (int i = 3; i <= args.narg(); i++) {
                    if (args.isstring(i))
                        params[i - 3] = args.toString();
                    else // TODO: more argument types
                        params[i - 3] = args.touserdata(i);

                    if (params[i - 3] == null)
                        types[i - 3] = null;
                    else
                        types[i - 3] = params[i - 3].getClass();
                }

                // TODO: resolve method with null arguments
                Method method = object.getClass().getDeclaredMethod(name, types);

                Object result = method.invoke(object, params);
                Log.i(TAG, "invokePrivateMethod(" + name + ")=" + result);
                if (result == null)
                    return LuaValue.NIL;
                else if (result instanceof String)
                    return LuaValue.valueOf((String) result);
                else // TODO: more LuaValue types
                    return LuaValue.userdataOf(result);
            } catch (Throwable ex) {
                Log.e(TAG, Log.getStackTraceString(ex));
                return LuaValue.NIL;
            }
        }
    }
}
