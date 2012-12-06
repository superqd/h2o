
package water.api;

import init.Boot;
import java.io.*;
import java.util.HashMap;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import water.H2O;
import water.NanoHTTPD;
import water.web.Page.PageError;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.gson.JsonObject;
import java.net.ServerSocket;

/** This is a simple web server. */
public class RequestServer extends NanoHTTPD {

  // cache of all loaded resources
  private static final ConcurrentHashMap<String,byte[]> _cache = new ConcurrentHashMap();
  private static final HashMap<String,Request> _requests = new HashMap();
  private static final Request _http404;
  private static final Request _http500;

  // initialization ------------------------------------------------------------
  static {
    _http404 = registerRequest(new HTTP404(), HTTP404.NAME);
    _http500 = registerRequest(new HTTP500(), HTTP500.NAME);
    Request.addToNavbar(registerRequest(new Cloud()),"Cloud");


    Request.initializeNavBar();
  }

  /** Registers the request with the request server.
   *
   * returns the request so that it can be further updated.
   *
   * @param req
   * @param href
   * @return
   */
  protected static Request registerRequest(Request req, String href) {
    assert (! href.endsWith(HTML_EXT)) : "Hrefs of webpages should not end with .html";
    assert (! _requests.containsKey(href)) : "Request with href "+href+" already registered";
    req.setHref(href);
    _requests.put(href,req);
    return req;
  }

  protected static Request registerRequest(Request req) {
    return registerRequest(req, req.getClass().getSimpleName());

  }

  // Keep spinning until we get to launch the NanoHTTPD
  public static void start() {
    new Thread( new Runnable() {
        public void run()  {
          while( true ) {
            try {
              // Try to get the NanoHTTP daemon started
              new RequestServer(new ServerSocket(12345));
              break;
            } catch ( Exception ioe ) {
              System.err.println("Launching NanoHTTP server got "+ioe);
              try { Thread.sleep(1000); } catch( InterruptedException e ) { } // prevent denial-of-service
            }
          }
        }
      }).start();
  }

  // uri serve -----------------------------------------------------------------

  public static final String HTML_EXT = ".html";

  @Override public NanoHTTPD.Response serve( String uri, String method, Properties header, Properties parms, Properties files ) {
    // Jack priority for user-visible requests
    Thread.currentThread().setPriority(Thread.MAX_PRIORITY-1);
    // update arguments and determine control variables
    if (uri.isEmpty()) uri = "/";
    boolean isHTML = uri.equals("/") || uri.endsWith(HTML_EXT);
    // get rid of the .html suffix
    if (uri.endsWith(HTML_EXT))
      uri = uri.substring(0,uri.length()-HTML_EXT.length());
    try {
      // determine if we have known resource
      Request request = _requests.get(uri.substring(1));
      // if the request is not know, treat as resource request, or 404 if not
      // found
      if (request == null)
        return getResource(uri);
      // otherwise unify get & post arguments
      parms.putAll(files);
      // call the request
      return request.dispatch(this,parms,isHTML);
    } catch (Exception e) {
      // make sure that no Exception is ever thrown out from the request
      parms.setProperty(Request.JSON_ERROR,e.getMessage());
      return _requests.get(_http500.href()).dispatch(this,parms,isHTML);
    }
  }

  private RequestServer( ServerSocket socket ) throws IOException {
    super(socket,null);
  }

  // Resource loading ----------------------------------------------------------

  // Returns the response containing the given uri with the appropriate mime
  // type.
  private NanoHTTPD.Response getResource(String uri) {
    byte[] bytes = _cache.get(uri);
    if( bytes == null ) {
      InputStream resource = Boot._init.getResource2(uri);
      if (resource != null) {
        try {
          bytes = ByteStreams.toByteArray(resource);
        } catch( IOException e ) { }
        byte[] res = _cache.putIfAbsent(uri,bytes);
        if( res != null ) bytes = res; // Racey update; take what is in the _cache
      }
      Closeables.closeQuietly(resource);
    }
    if (bytes == null) {
      // make sure that no Exception is ever thrown out from the request
      Properties parms = new Properties();
      parms.setProperty(Request.JSON_ERROR,uri);
      return _requests.get(_http404.href()).dispatch(this,parms,true);
    }
    String mime = NanoHTTPD.MIME_DEFAULT_BINARY;
    if (uri.endsWith(".css"))
      mime = "text/css";
    else if (uri.endsWith(".html"))
      mime = "text/html";
    return new NanoHTTPD.Response(NanoHTTPD.HTTP_OK,mime,new ByteArrayInputStream(bytes));
  }

}
