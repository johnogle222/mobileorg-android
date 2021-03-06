package com.matburt.mobileorg;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.ArrayList;
import java.lang.IllegalArgumentException;

import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.content.res.Resources.NotFoundException;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.util.Log;
import android.text.TextUtils;
import org.apache.http.params.CoreProtocolPNames;
import org.apache.http.params.HttpParams;

public class WebDAVSynchronizer extends Synchronizer
{
    private boolean pushedStageFile = false;

    WebDAVSynchronizer(Context parentContext) {
        this.rootContext = parentContext;
        this.r = this.rootContext.getResources();
        this.appdb = new MobileOrgDatabase((Context)parentContext);
        this.appSettings = PreferenceManager.getDefaultSharedPreferences(
                                   parentContext.getApplicationContext());
    }

    public void push() throws NotFoundException, ReportableError {
        String urlActual = this.getRootUrl() + "mobileorg.org";
        String storageMode = this.appSettings.getString("storageMode", "");
        BufferedReader reader = null;
        String fileContents = "";
        this.pushedStageFile = false;

        if (storageMode.equals("internal") || storageMode == null) {
            FileInputStream fs;
            try {
                fs = rootContext.openFileInput("mobileorg.org");
                reader = new BufferedReader(new InputStreamReader(fs));
            }
            catch (java.io.FileNotFoundException e) {
            	Log.i(LT, "Did not find mobileorg.org file, not pushing.");
                return;
            }
        }
        else if (storageMode.equals("sdcard")) {
            try {
                File root = Environment.getExternalStorageDirectory();
                File morgDir = new File(root, "mobileorg");
                File morgFile = new File(morgDir, "mobileorg.org");
                if (!morgFile.exists()) {
                    Log.i(LT, "Did not find mobileorg.org file, not pushing.");
                    return;
                }
                FileReader orgFReader = new FileReader(morgFile);
                reader = new BufferedReader(orgFReader);
            }
            catch (java.io.IOException e) {
                throw new ReportableError(
                		r.getString(R.string.error_file_read, "mobileorg.org"),
                		e);
            }
        }
        else {
        	throw new ReportableError(
        			r.getString(R.string.error_local_storage_method_unknown, storageMode),
        			null);
        }

        String thisLine = "";
        try {
            while ((thisLine = reader.readLine()) != null) {
                fileContents += thisLine + "\n";
            }
        }
        catch (java.io.IOException e) {
        	throw new ReportableError(
            		r.getString(R.string.error_file_read, "mobileorg.org"),
            		e);
        }

        DefaultHttpClient httpC = this.createConnection(
                                    this.appSettings.getString("webUser", ""),
                                    this.appSettings.getString("webPass", ""));
        this.appendUrlFile(urlActual, httpC, fileContents);

        if (this.pushedStageFile) {
            this.removeFile("mobileorg.org");
        }
    }

    public boolean checkReady() {
        if (this.appSettings.getString("webUrl","").equals(""))
            return false;
        return true;
    }

    public void pull() throws NotFoundException, ReportableError {
        Pattern checkUrl = Pattern.compile("http.*\\.(?:org|txt)$");
        String url = this.appSettings.getString("webUrl", "");
        if (!checkUrl.matcher(url).find()) {
        	throw new ReportableError(
            		r.getString(R.string.error_bad_url, url),
            		null);
        }

        //Get the index org file
        String masterStr = this.fetchOrgFile(url);
        if (masterStr.equals("")) {
            throw new ReportableError(
            		r.getString(R.string.error_file_not_found, url),
            		null);
        }
        HashMap<String, String> masterList = this.getOrgFilesFromMaster(masterStr);
        ArrayList<HashMap<String, Boolean>> todoLists = this.getTodos(masterStr);
        ArrayList<ArrayList<String>> priorityLists = this.getPriorities(masterStr);
        this.appdb.setTodoList(todoLists);
        this.appdb.setPriorityList(priorityLists);
        String urlActual = this.getRootUrl();

        //Get checksums file
        masterStr = this.fetchOrgFile(urlActual + "checksums.dat");
        HashMap<String, String> newChecksums = this.getChecksums(masterStr);
        HashMap<String, String> oldChecksums = this.appdb.getChecksums();

        //Get other org files
        for (String key : masterList.keySet()) {
            if (oldChecksums.containsKey(key) &&
                newChecksums.containsKey(key) &&
                oldChecksums.get(key).equals(newChecksums.get(key)))
                continue;
            Log.d(LT, "Fetching: " +
                  key + ": " + urlActual + masterList.get(key));
            String fileContents = this.fetchOrgFile(urlActual +
                                                    masterList.get(key));
            String storageMode = this.appSettings.getString("storageMode", "");
            BufferedWriter writer = new BufferedWriter(new StringWriter());

            if (storageMode.equals("internal") || storageMode == null) {
                FileOutputStream fs;
                try {
                    String normalized = masterList.get(key).replace("/", "_");
                    fs = rootContext.openFileOutput(normalized, 0);
                    writer = new BufferedWriter(new OutputStreamWriter(fs));
                }
                catch (java.io.FileNotFoundException e) {
                	throw new ReportableError(
                    		r.getString(R.string.error_file_not_found, key),
                    		e);
                }
            }
            else if (storageMode.equals("sdcard")) {

                try {
                    File root = Environment.getExternalStorageDirectory();
                    File morgDir = new File(root, "mobileorg");
                    morgDir.mkdir();
                    if (morgDir.canWrite()){
                        File orgFileCard = new File(morgDir, masterList.get(key));
                        File orgDirCard = orgFileCard.getParentFile();
                        orgDirCard.mkdirs();
                        FileWriter orgFWriter = new FileWriter(orgFileCard);
                        writer = new BufferedWriter(orgFWriter);
                    }
                    else {
                        throw new ReportableError(
                        		r.getString(R.string.error_file_permissions, morgDir.getAbsolutePath()),
                        		null);
                    }
                } catch (java.io.IOException e) {
                    throw new ReportableError(
                    		"IO Exception initializing writer on sdcard file",
                    		e);
                }
            }
            else {
                throw new ReportableError(
                		r.getString(R.string.error_local_storage_method_unknown, storageMode),
                		null);
            }

            try {
            	writer.write(fileContents);
            	this.appdb.addOrUpdateFile(masterList.get(key), key, newChecksums.get(key));
                writer.flush();
                writer.close();
            }
            catch (java.io.IOException e) {
                throw new ReportableError(
                		r.getString(R.string.error_file_write, masterList.get(key)),
                		e);
            }
        }
    }

    private String fetchOrgFile(String orgUrl) throws NotFoundException, ReportableError {
        DefaultHttpClient httpC = this.createConnection(
                                      this.appSettings.getString("webUser", ""),
                                      this.appSettings.getString("webPass", ""));
        InputStream mainFile;
        try {
            mainFile = this.getUrlStream(orgUrl, httpC);
        }
        catch (IllegalArgumentException e) {
            throw new ReportableError(
                    r.getString(R.string.error_invalid_url, orgUrl),
                    e);
        }

        String masterStr = "";
        try {
            if (mainFile == null) {
                Log.w(LT, "Stream is null");
                return "";
            }
            masterStr = this.ReadInputStream(mainFile);
        }
        catch (IOException e) {
            throw new ReportableError(
            		r.getString(R.string.error_url_fetch, orgUrl),
            		e);
        }
        return masterStr;
    }

    private String getRootUrl() throws NotFoundException, ReportableError {
        URL manageUrl = null;
        try {
            manageUrl = new URL(this.appSettings.getString("webUrl", ""));
        }
        catch (MalformedURLException e) {
            throw new ReportableError(
            		r.getString(R.string.error_bad_url,
            				(manageUrl == null) ? "" : manageUrl.toString()),
            		e);
        }

        String urlPath =  manageUrl.getPath();
        String[] pathElements = urlPath.split("/");
        String directoryActual = "/";
        if (pathElements.length > 1) {
            for (int idx = 0; idx < pathElements.length - 1; idx++) {
                if (pathElements[idx].length() > 0) {
                    directoryActual += pathElements[idx] + "/";
                }
            }
        }
        return manageUrl.getProtocol() + "://" +
            manageUrl.getAuthority() + directoryActual;
    }

    private DefaultHttpClient createConnection(String user, String password) {
        DefaultHttpClient httpClient = new DefaultHttpClient();
        HttpParams params = httpClient.getParams();
        SchemeRegistry schemeRegistry = new SchemeRegistry();
        schemeRegistry.register (new Scheme ("http",
                                             PlainSocketFactory.getSocketFactory (), 80));
        SSLSocketFactory sslSocketFactory = SSLSocketFactory.getSocketFactory();
        sslSocketFactory.setHostnameVerifier(SSLSocketFactory.BROWSER_COMPATIBLE_HOSTNAME_VERIFIER);
        schemeRegistry.register (new Scheme ("https",
                                             sslSocketFactory, 443));
        ThreadSafeClientConnManager cm = new ThreadSafeClientConnManager (
                                                  params, schemeRegistry);

        UsernamePasswordCredentials bCred = new UsernamePasswordCredentials(user, password);
        BasicCredentialsProvider cProvider = new BasicCredentialsProvider();
        cProvider.setCredentials(AuthScope.ANY, bCred);

        params.setBooleanParameter(CoreProtocolPNames.USE_EXPECT_CONTINUE, false);
        httpClient.setParams(params);

        DefaultHttpClient nHttpClient = new DefaultHttpClient(cm, params);
        nHttpClient.setCredentialsProvider(cProvider);
        return nHttpClient;
    }

    private InputStream getUrlStream(String url, DefaultHttpClient httpClient) throws NotFoundException, ReportableError {
        try {
            HttpResponse res = httpClient.execute(new HttpGet(url));
            
            StatusLine status = res.getStatusLine();
            if (status.getStatusCode() == 404) {
                return null;
            }

            if (status.getStatusCode() < 200 || status.getStatusCode() > 299) {
            	throw new ReportableError(
            			r.getString(R.string.error_url_fetch_detail,
                                    url,
                                    status.getReasonPhrase()),
            			null);
            }
            
            return res.getEntity().getContent();
        }
        catch (IOException e) {
            Log.e(LT, e.toString());
            Log.w(LT, "Failed to get URL");
            return null; //handle exception
        }
    }

    private void putUrlFile(String url,
                           DefaultHttpClient httpClient,
                           String content) throws NotFoundException, ReportableError {
        try {
            HttpPut httpPut = new HttpPut(url);
            httpPut.setEntity(new StringEntity(content, "UTF-8"));
            HttpResponse response = httpClient.execute(httpPut);
            StatusLine statResp = response.getStatusLine();
            if (statResp.getStatusCode() >= 400) {
                this.pushedStageFile = false;
            } else {
                this.pushedStageFile = true;
            }

            httpClient.getConnectionManager().shutdown();
        }
        catch (UnsupportedEncodingException e) {
        	throw new ReportableError(
        			r.getString(R.string.error_unsupported_encoding, "mobileorg.org"),
        			e);
        }
        catch (IOException e) {
        	throw new ReportableError(
        			r.getString(R.string.error_url_put, url),
        			e);
        }
    }
    
    private void appendUrlFile(String url,
    							DefaultHttpClient httpClient,
    							String content) throws NotFoundException, ReportableError {
    	String originalContent = this.fetchOrgFile(url);
    	String newContent = originalContent + '\n' + content;
    	this.putUrlFile(url, httpClient, newContent);
    }

    private String ReadInputStream(InputStream in) throws IOException {
        StringBuffer stream = new StringBuffer();
        byte[] b = new byte[4096];
        for (int n; (n = in.read(b)) != -1;) {
            stream.append(new String(b, 0, n));
        }
        return stream.toString();
    }
}

