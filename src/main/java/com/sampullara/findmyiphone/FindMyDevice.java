package com.sampullara.findmyiphone;

import org.apache.http.HttpResponse;
import org.apache.http.NameValuePair;
import org.apache.http.client.CookieStore;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.cookie.Cookie;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.codehaus.jackson.JsonFactory;
import org.codehaus.jackson.JsonGenerator;
import org.codehaus.jackson.JsonParser;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FindMyDevice extends HttpServlet {

  @Override
  protected void doGet(HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse) throws ServletException, IOException {
    String username = httpServletRequest.getParameter("username");
    String password = httpServletRequest.getParameter("password");
    if (username == null || password == null) {
      httpServletResponse.sendError(400, "You must specify a MobileMe username and password");
      return;
    }

    httpServletResponse.setContentType("application/json");
    writeDeviceLocations(httpServletResponse.getWriter(), username, password);
  }

  private static void writeDeviceLocations(Writer out, String username, String password) throws IOException {
    // Initialize the HTTP client
    DefaultHttpClient hc = new DefaultHttpClient();

    // Authorize with Apple's mobile me service
    HttpGet auth = new HttpGet("https://auth.apple.com/authenticate?service=DockStatus&realm=primary-me&formID=loginForm&username=" + username + "&password=" + password + "&returnURL=aHR0cHM6Ly9zZWN1cmUubWUuY29tL3dvL1dlYk9iamVjdHMvRG9ja1N0YXR1cy53b2Evd2EvdHJhbXBvbGluZT9kZXN0aW5hdGlvblVybD0vYWNjb3VudA%3D%3D");
    hc.execute(auth);
    auth.abort();

    // Pull the isc-secure.me.com cookie out so we can set the X-Mobileme-Isc header properly
    String isc = readCookies(hc);

    // Get access to the devices and find out their ids
    HttpPost devicemgmt = new HttpPost("https://secure.me.com/wo/WebObjects/DeviceMgmt.woa/?lang=en");
    devicemgmt.addHeader("X-Mobileme-Version", "1.0");
    devicemgmt.addHeader("X-Mobileme-Isc", isc);
    devicemgmt.setEntity(new UrlEncodedFormEntity(new ArrayList<NameValuePair>()));
    HttpResponse devicePage = hc.execute(devicemgmt);

    // Extract the device ids from their html encoded in json
    ByteArrayOutputStream os = new ByteArrayOutputStream();
    devicePage.getEntity().writeTo(os);
    os.close();
    Matcher m = Pattern.compile("DeviceMgmt.deviceIdMap\\['[0-9]+'\\] = '([a-z0-9]+)';").matcher(new String(os.toByteArray()));
    List<String> deviceList = new ArrayList<String>();
    while (m.find()) {
      deviceList.add(m.group(1));
    }

    // For each available device, get the location
    JsonFactory jf = new JsonFactory();
    JsonGenerator jg = jf.createJsonGenerator(out);
    jg.writeStartObject();
    for (String device : deviceList) {
      HttpPost locate = new HttpPost("https://secure.me.com/wo/WebObjects/DeviceMgmt.woa/wa/LocateAction/locateStatus");
      locate.addHeader("X-Mobileme-Version", "1.0");
      locate.addHeader("X-Mobileme-Isc", isc);
      locate.setEntity(new StringEntity("postBody={\"deviceId\": \"" + device + "\", \"deviceOsVersion\": \"7A341\"}"));
      locate.setHeader("Content-type", "application/json");
      HttpResponse location = hc.execute(locate);
      InputStream inputStream = location.getEntity().getContent();
      JsonParser jp = jf.createJsonParser(inputStream);
      jp.nextToken(); // ugly
      jg.writeFieldName(device);
      jg.copyCurrentStructure(jp);
      inputStream.close();
    }
    jg.close();
    out.close();
  }

  private static String readCookies(DefaultHttpClient hc) {
    CookieStore cookies = hc.getCookieStore();
    for (Cookie cookie : cookies.getCookies()) {
      if (cookie.getName().equals("isc-secure.me.com")) {
        return cookie.getValue();
      }
    }
    return null;
  }
}
