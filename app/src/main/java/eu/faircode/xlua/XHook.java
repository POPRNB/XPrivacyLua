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

import android.app.ActivityManager;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.os.Build;
import android.telephony.SmsManager;
import android.text.TextUtils;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Scanner;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class XHook {
    private final static String TAG = "XLua.XHook";

    private boolean builtin = false;
    private String collection;
    private String group;
    private String name;
    private String author;

    private String className;
    private String resolvedClassName = null;
    private String methodName;
    private String[] parameterTypes;
    private String returnType;

    private int minSdk;
    private int maxSdk;
    private String[] excludePackages;
    private boolean enabled;
    private boolean optional;
    private boolean usage;
    private boolean notify;

    private String luaScript;

    private XHook() {
    }

    public void validate() {
        if (TextUtils.isEmpty(this.collection))
            throw new IllegalArgumentException("collection missing");
        if (TextUtils.isEmpty(this.group))
            throw new IllegalArgumentException("group missing");
        if (TextUtils.isEmpty(this.name))
            throw new IllegalArgumentException("name missing");
        if (TextUtils.isEmpty(this.author))
            throw new IllegalArgumentException("author missing");
        if (TextUtils.isEmpty(this.className))
            throw new IllegalArgumentException("class name missing");
        if (TextUtils.isEmpty(this.methodName))
            throw new IllegalArgumentException("method name missing");
        if (parameterTypes == null)
            throw new IllegalArgumentException("parameter types missing");
        if (TextUtils.isEmpty(this.luaScript))
            throw new IllegalArgumentException("Lua script missing");
    }

    public String getId() {
        return this.collection + "." + this.name;
    }

    public boolean isBuiltin() {
        return this.builtin;
    }

    @SuppressWarnings("unused")
    public String getCollection() {
        return this.collection;
    }

    public String getGroup() {
        return this.group;
    }

    public String getName() {
        return this.name;
    }

    @SuppressWarnings("unused")
    public String getAuthor() {
        return this.author;
    }

    @SuppressWarnings("unused")
    public String getClassName() {
        return this.className;
    }

    public String getResolvedClassName() {
        return (this.resolvedClassName == null ? this.className : this.resolvedClassName);
    }

    public String getMethodName() {
        return this.methodName;
    }

    public String[] getParameterTypes() {
        return this.parameterTypes;
    }

    public String getReturnType() {
        return this.returnType;
    }

    public boolean isAvailable(String packageName) {
        if (!this.enabled)
            return false;

        if (Build.VERSION.SDK_INT < this.minSdk || Build.VERSION.SDK_INT > this.maxSdk)
            return false;

        if (packageName == null)
            return true;

        if (this.excludePackages == null)
            return true;

        boolean included = true;
        for (String excluded : this.excludePackages)
            if (Pattern.matches(excluded, packageName)) {
                included = false;
                break;
            }

        if (!included)
            Log.i(TAG, "Excluded " + this.getId() + " for " + packageName);

        return included;
    }

    public boolean isOptional() {
        return this.optional;
    }

    public boolean doUsage() {
        return this.usage;
    }

    public boolean doNotify() {
        return this.notify;
    }

    public String getLuaScript() {
        return this.luaScript;
    }

    public void resolveClassName(Context context) {
        if ("android.app.ActivityManager".equals(this.className)) {
            Object service = context.getSystemService(ActivityManager.class);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.appwidget.AppWidgetManager".equals(this.className)) {
            Object service = context.getSystemService(AppWidgetManager.class);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.media.AudioManager".equals(this.className)) {
            Object service = context.getSystemService(AudioManager.class);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.hardware.camera2.CameraManager".equals(this.className)) {
            Object service = context.getSystemService(CameraManager.class);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.content.ContentResolver".equals(this.className)) {
            this.resolvedClassName = context.getContentResolver().getClass().getName();

        } else if ("android.content.pm.PackageManager".equals(this.className)) {
            this.resolvedClassName = context.getPackageManager().getClass().getName();

        } else if ("android.hardware.SensorManager".equals(this.className)) {
            Object service = context.getSystemService(SensorManager.class);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.telephony.SmsManager".equals(this.className)) {
            Object service = SmsManager.getDefault();
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.telephony.TelephonyManager".equals(this.className)) {
            Object service = context.getSystemService(Context.TELEPHONY_SERVICE);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();

        } else if ("android.net.wifi.WifiManager".equals(this.className)) {
            Object service = context.getSystemService(Context.WIFI_SERVICE);
            if (service != null)
                this.resolvedClassName = service.getClass().getName();
        }
    }

    // Read hook definitions from asset file
    static ArrayList<XHook> readHooks(Context context, String apk) throws IOException, JSONException {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apk);
            ZipEntry zipEntry = zipFile.getEntry("assets/hooks.json");
            if (zipEntry == null)
                throw new IllegalArgumentException("assets/hooks.json not found in " + apk);

            InputStream is = null;
            try {
                is = zipFile.getInputStream(zipEntry);
                String json = new Scanner(is).useDelimiter("\\A").next();
                ArrayList<XHook> hooks = new ArrayList<>();
                JSONArray jarray = new JSONArray(json);
                for (int i = 0; i < jarray.length(); i++) {
                    XHook hook = XHook.fromJSONObject(jarray.getJSONObject(i));
                    hook.builtin = true;

                    // Link script
                    String script = hook.getLuaScript();
                    if (script.startsWith("@")) {
                        ZipEntry luaEntry = zipFile.getEntry("assets/" + script.substring(1) + ".lua");
                        if (luaEntry == null)
                            throw new IllegalArgumentException(script + " not found for " + hook.getId());
                        else {
                            InputStream lis = null;
                            try {
                                lis = zipFile.getInputStream(luaEntry);
                                script = new Scanner(lis).useDelimiter("\\A").next();
                                hook.luaScript = script;
                            } finally {
                                if (lis != null)
                                    try {
                                        lis.close();
                                    } catch (IOException ignored) {
                                    }
                            }
                        }
                    }

                    hooks.add(hook);
                }

                return hooks;
            } finally {
                if (is != null)
                    try {
                        is.close();
                    } catch (IOException ignored) {
                    }
            }
        } finally {
            if (zipFile != null)
                try {
                    zipFile.close();
                } catch (IOException ignored) {
                }
        }
    }

    String toJSON() throws JSONException {
        return toJSONObject().toString(2);
    }

    JSONObject toJSONObject() throws JSONException {
        JSONObject jroot = new JSONObject();

        jroot.put("builtin", this.builtin);
        jroot.put("collection", this.collection);
        jroot.put("group", this.group);
        jroot.put("name", this.name);
        jroot.put("author", this.author);

        jroot.put("className", this.className);
        if (this.resolvedClassName != null)
            jroot.put("resolvedClassName", this.resolvedClassName);
        jroot.put("methodName", this.methodName);

        JSONArray jparam = new JSONArray();
        for (int i = 0; i < this.parameterTypes.length; i++)
            jparam.put(this.parameterTypes[i]);
        jroot.put("parameterTypes", jparam);

        if (this.returnType != null)
            jroot.put("returnType", this.returnType);

        jroot.put("minSdk", this.minSdk);
        jroot.put("maxSdk", this.maxSdk);

        if (this.excludePackages != null)
            jroot.put("excludePackages", TextUtils.join(",", this.excludePackages));

        jroot.put("enabled", this.enabled);
        jroot.put("optional", this.optional);
        jroot.put("usage", this.usage);
        jroot.put("notify", this.notify);

        jroot.put("luaScript", this.luaScript);

        return jroot;
    }

    static XHook fromJSON(String json) throws JSONException {
        return fromJSONObject(new JSONObject(json));
    }

    static XHook fromJSONObject(JSONObject jroot) throws JSONException {
        XHook hook = new XHook();

        hook.builtin = (jroot.has("builtin") ? jroot.getBoolean("builtin") : false);
        hook.collection = jroot.getString("collection");
        hook.group = jroot.getString("group");
        hook.name = jroot.getString("name");
        hook.author = jroot.getString("author");

        hook.className = jroot.getString("className");
        hook.resolvedClassName = (jroot.has("resolvedClassName") ? jroot.getString("resolvedClassName") : null);
        hook.methodName = jroot.getString("methodName");

        JSONArray jparam = jroot.getJSONArray("parameterTypes");
        hook.parameterTypes = new String[jparam.length()];
        for (int i = 0; i < jparam.length(); i++)
            hook.parameterTypes[i] = jparam.getString(i);

        hook.returnType = (jroot.has("returnType") ? jroot.getString("returnType") : null);

        hook.minSdk = jroot.getInt("minSdk");
        hook.maxSdk = (jroot.has("maxSdk") ? jroot.getInt("maxSdk") : 999);

        hook.excludePackages = (jroot.has("excludePackages")
                ? jroot.getString("excludePackages").split(",") : null);

        hook.enabled = (jroot.has("enabled") ? jroot.getBoolean("enabled") : true);
        hook.optional = (jroot.has("optional") ? jroot.getBoolean("optional") : false);
        hook.usage = (jroot.has("usage") ? jroot.getBoolean("usage") : true);
        hook.notify = (jroot.has("notify") ? jroot.getBoolean("notify") : false);

        hook.luaScript = jroot.getString("luaScript");

        return hook;
    }

    @Override
    public String toString() {
        return this.getId() + "@" + this.className + ":" + this.methodName;
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof XHook))
            return false;
        XHook other = (XHook) obj;
        return this.getId().equals(other.getId());
    }

    @Override
    public int hashCode() {
        return this.getId().hashCode();
    }
}
