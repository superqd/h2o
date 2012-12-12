
package water.api;

import com.google.gson.JsonObject;
import java.util.regex.Pattern;

/**
 *
 * @author peta
 */
public class RequestStatics {

  public static final String JSON_ERROR = "error";
  public static final String JSON_STATUS = "status";
  public static final String JSON_STATUS_DONE = "done";


  public static final String JSON_KEY = "key";
  public static final String JSON_VALUE = "value";
  public static final String JSON_VALUE_SIZE = "value_size";
  public static final String JSON_RF = "rf";
  public static final String JSON_DATA_KEY = "key";
  public static final String JSON_NUM_TREES = "ntree";
  public static final String JSON_DEPTH = "depth";
  public static final String JSON_SAMPLE = "sample";
  public static final String JSON_BIN_LIMIT = "bin_limit";
  public static final String JSON_GINI = "gini";
  public static final String JSON_SEED = "seed";
  public static final String JSON_PARALLEL = "parallel";
  public static final String JSON_MODEL_KEY = "model_key";
  public static final String JSON_CLASS = "class";
  public static final String JSON_IGNORE = "ignore";
  public static final String JSON_OOBEE = "oobee";
  public static final String JSON_FEATURES = "features";
  public static final String JSON_STRATIFY = "stratify";
  public static final String JSON_STRATA = "strata";
  public static final String JSON_WEIGHTS = "classWt";
  public static final String JSON_FILTER = "filter";
  public static final String JSON_KEYS = "keys";
  public static final String JSON_LIMIT = "limit";
  public static final String JSON_COLUMNS = "columns";
  public static final String JSON_CHECKARG_ARG = "__arg__";

  public String requestName() {
    return getClass().getSimpleName();
  }


  /** Request type.
   *
   * Requests can have multiple types. Basic types include the plain json type
   * in which the result is returned as a JSON object, a html type that acts as
   * the webpage, or the help type that displays the extended help for the
   * request.
   *
   * The wiki type is also added that displays the markup of the wiki that
   * should be used to document the request as per Matt's suggestion.
   *
   * NOTE the requests are distinguished by their suffixes. Please make the
   * suffix start with the dot character to avoid any problems with request
   * names.
   */
  public static enum RequestType {
    json(".json"), ///< json type request, a result is a JSON structure
    www(".html"), ///< webpage request
    help(".help"), ///< should display the help on the given request
    wiki(".wiki"), ///< displays the help for the given request in a markup for wiki
    query(".query"), ///< fDisplays the query for the argument in html mode
    checkArg(".checkArg"), ///< checks single argument specified by the __arg__ argument
    ;
    /** Suffix of the request - extension of the URL.
     */
    public final String _suffix;

    RequestType(String suffix) {
      _suffix = suffix;
    }

    /** Returns the request type of a given URL. JSON request type is the default
     * type when the extension from the URL cannot be determined.
     */
    public static RequestType requestType(String requestUrl) {
      if (requestUrl.endsWith(www._suffix))
        return www;
      if (requestUrl.endsWith(help._suffix))
        return help;
      if (requestUrl.endsWith(wiki._suffix))
        return wiki;
      if (requestUrl.endsWith(query._suffix))
        return query;
      if (requestUrl.endsWith(checkArg._suffix))
        return checkArg;
      return json;
    }

    /** Returns the name of the request, that is the request url without the
     * request suffix.
     */
    public String requestName(String requestUrl) {
      String result = (requestUrl.endsWith(_suffix)) ? requestUrl.substring(0, requestUrl.length()-_suffix.length()) : requestUrl;
      if (result.charAt(0) == '/')
        return result.substring(1);
      return result;
    }
  }


  private static Pattern _correctJsonName = Pattern.compile("^[_a-z][_a-z0-9]*$");

  /** Checks if the given JSON name is valid. A valid JSON name is a sequence of
   * small letters, numbers and underscores that does not start with number.
   */
  public static boolean checkJsonName(String name) {
    return _correctJsonName.matcher(name).find();
  }

  protected static JsonObject jsonError(String error) {
    JsonObject result = new JsonObject();
    result.addProperty(JSON_ERROR, error);
    return result;
  }

}
