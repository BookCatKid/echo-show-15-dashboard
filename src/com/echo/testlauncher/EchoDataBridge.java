package com.echo.testlauncher;

import android.system.Os;
import android.system.StructStat;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.regex.*;

/** Privileged read-only Echo source adapter. Runs separately from the dashboard. */
public final class EchoDataBridge {
    private static String text(InputStream in) throws Exception {
        ByteArrayOutputStream out=new ByteArrayOutputStream(); byte[] b=new byte[8192];int n;
        while((n=in.read(b))!=-1) { out.write(b,0,n);if(out.size()>4000000) throw new IOException("Input too large"); }
        return new String(out.toByteArray(),StandardCharsets.UTF_8);
    }
    private static JSONObject weather() throws Exception {
        Process p=new ProcessBuilder("/system/bin/runcon","u:r:shell:s0","/system/bin/content","query","--uri","content://com.amazon.paladin.weather.provider/weather").redirectErrorStream(true).start();
        String result=text(p.getInputStream());p.waitFor();
        if(!result.startsWith("Row:")) throw new IOException("Echo weather is unavailable");
        JSONObject w=new JSONObject();
        Matcher m=Pattern.compile("(?:Row: \\d+ |, )(weatherConditionCode|weatherSummary|location|temperatureScale|temperatureValue)=(.*?)(?=, [A-Za-z]+=|$)",Pattern.DOTALL).matcher(result.trim());
        while(m.find()) { String k=m.group(1),v=m.group(2); if(k.equals("temperatureValue")||k.equals("weatherConditionCode")) w.put(k,Integer.parseInt(v));else w.put(k,v); }
        if(!w.has("temperatureValue")) throw new IOException("No cached weather");
        w.put("updatedAt",System.currentTimeMillis());return w;
    }
    public static void main(String[] args) throws Exception {
        String directory=args.length>0?args[0]:"/data/user/0/com.echo.testlauncher/files";
        JSONObject previous=new JSONObject(); long weatherTime=0;
        while(true) {
            try {
                if(System.currentTimeMillis()-weatherTime>300000) {
                    try { previous.put("weather",weather());weatherTime=System.currentTimeMillis(); }catch(Exception e) { System.err.println("Echo weather read pending"); }
                }
                File source=new File("/data/user/0/com.amazon.paladin/files/dashboard-calendar.json");
                if(source.isFile()) { try(FileInputStream in=new FileInputStream(source)) { previous.put("calendar",CalendarNormalizer.normalize(new JSONArray(text(in)), source.lastModified())); } }
                else previous.put("calendar",new JSONObject().put("events",new JSONArray()).put("status","Connecting to Echo calendar"));
                File dir=new File(directory);
                if(dir.isDirectory()) {
                    StructStat stat=Os.stat(directory);
                    File tmp=new File(dir,"echo-data.tmp");
                    try(FileOutputStream out=new FileOutputStream(tmp)) { out.write(previous.toString().getBytes(StandardCharsets.UTF_8)); }
                    Os.chown(tmp.getAbsolutePath(),stat.st_uid,stat.st_gid);Os.chmod(tmp.getAbsolutePath(),0600);
                    new ProcessBuilder("/system/bin/restorecon",tmp.getAbsolutePath()).start().waitFor();
                    Os.rename(tmp.getAbsolutePath(),new File(dir,"echo-data.json").getAbsolutePath());
                }
            } catch(Exception e) { System.err.println("Echo data export pending: "+e.getClass().getSimpleName()); }
            Thread.sleep(30000);
        }
    }
}
