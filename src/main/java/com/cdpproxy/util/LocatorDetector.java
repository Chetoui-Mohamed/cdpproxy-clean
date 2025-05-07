package com.cdpproxy.util;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utility class for extracting locator information from various CDP messages
 */
public class LocatorDetector {
    private static final Logger logger = Logger.getLogger(LocatorDetector.class.getName());

    // Patterns for extracting locators from function declarations
    private static final Pattern SELECTOR_PATTERN = Pattern.compile("querySelector\\(['\"]([^'\"]+)['\"]\\)");
    private static final Pattern XPATH_PATTERN = Pattern.compile("xpath=(.+?)(?:\"|}|\\s|$)");

    /**
     * Extract selector information from a Playwright CDP message
     */
    public static SelectorInfo extractSelector(JSONObject message) {
        try {
            if (!message.has("method") || !message.has("params")) {
                return null;
            }

            String method = message.getString("method");
            JSONObject params = message.getJSONObject("params");

            // Different methods require different extraction strategies
            if (method.equals("DOM.querySelector") || method.equals("DOM.querySelectorAll")) {
                // Direct DOM queries - easy to extract
                if (params.has("selector")) {
                    return new SelectorInfo(params.getString("selector"), "CSS");
                }
            } else if (method.equals("Runtime.callFunctionOn")) {
                // Complex method used by Playwright for most operations
                return extractSelectorFromFunction(params);
            } else if (method.equals("Runtime.evaluate")) {
                // Sometimes used for simple evaluations
                if (params.has("expression")) {
                    String expression = params.getString("expression");
                    Matcher matcher = SELECTOR_PATTERN.matcher(expression);
                    if (matcher.find()) {
                        return new SelectorInfo(matcher.group(1), "CSS");
                    }

                    // Try XPath pattern
                    matcher = XPATH_PATTERN.matcher(expression);
                    if (matcher.find()) {
                        return new SelectorInfo(matcher.group(1), "XPATH");
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Error extracting selector: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extract selector from complex Runtime.callFunctionOn structure
     */
    private static SelectorInfo extractSelectorFromFunction(JSONObject params) {
        try {
            // Check if this is a utilityScript.evaluate call (Playwright pattern)
            if (params.has("functionDeclaration") &&
                    params.getString("functionDeclaration").contains("utilityScript.evaluate")) {

                if (!params.has("arguments") || params.getJSONArray("arguments").length() < 7) {
                    return null;
                }

                JSONArray arguments = params.getJSONArray("arguments");

                // The 7th argument (index 6) often contains info about the selector
                for (int i = 5; i < Math.min(8, arguments.length()); i++) {
                    JSONObject arg = arguments.getJSONObject(i);
                    if (!arg.has("value") || !(arg.get("value") instanceof JSONObject)) {
                        continue;
                    }

                    JSONObject value = arg.getJSONObject("value");

                    // Extract from nested structure
                    SelectorInfo info = extractFromNestedStructure(value);
                    if (info != null) {
                        return info;
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Error extracting selector from function: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extract selector from deeply nested JSON structure
     */
    private static SelectorInfo extractFromNestedStructure(JSONObject obj) {
        try {
            // Handle structures with 'o' array (common in Playwright CDP messages)
            if (obj.has("o") && obj.get("o") instanceof JSONArray) {
                JSONArray properties = obj.getJSONArray("o");

                // Look for the info property which contains selector details
                for (int i = 0; i < properties.length(); i++) {
                    JSONObject prop = properties.getJSONObject(i);

                    // Direct 'source' or 'css' property (easier case)
                    if (prop.has("k") && prop.has("v")) {
                        String key = prop.getString("k");
                        if ((key.equals("source") || key.equals("css")) && prop.get("v") instanceof String) {
                            String type = key.equals("css") ? "CSS" : "UNKNOWN";
                            return new SelectorInfo(prop.getString("v"), type);
                        }
                    }

                    // Deeper 'info' object that contains all selector details
                    if (prop.has("k") && prop.getString("k").equals("info") &&
                            prop.has("v") && prop.get("v") instanceof JSONObject) {

                        SelectorInfo info = extractFromInfoObject(prop.getJSONObject("v"));
                        if (info != null) {
                            return info;
                        }
                    }
                }
            }

            // Handle direct value property
            if (obj.has("v") && obj.get("v") instanceof String && obj.has("k")) {
                String key = obj.getString("k");
                if (key.equals("source") || key.equals("css") || key.equals("selector")) {
                    String type = key.equals("css") ? "CSS" : "UNKNOWN";
                    return new SelectorInfo(obj.getString("v"), type);
                }
            }
        } catch (Exception e) {
            logger.fine("Error in extractFromNestedStructure: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extract selector from the 'info' object
     */
    private static SelectorInfo extractFromInfoObject(JSONObject infoObj) {
        try {
            // Handle structures with 'o' array (common in Playwright CDP messages)
            if (infoObj.has("o") && infoObj.get("o") instanceof JSONArray) {
                JSONArray properties = infoObj.getJSONArray("o");

                String selector = null;
                String type = "UNKNOWN";

                // Look through all properties for selector info
                for (int i = 0; i < properties.length(); i++) {
                    JSONObject prop = properties.getJSONObject(i);

                    // Direct 'source' property
                    if (prop.has("k") && prop.getString("k").equals("source") &&
                            prop.has("v") && prop.get("v") instanceof String) {
                        selector = prop.getString("v");
                    }

                    // Engine type
                    if (prop.has("k") && prop.getString("k").equals("engine") &&
                            prop.has("v") && prop.get("v") instanceof String) {
                        type = prop.getString("v").toUpperCase();
                    }

                    // Look for parsed object with more details
                    if (prop.has("k") && prop.getString("k").equals("parsed") &&
                            prop.has("v") && prop.get("v") instanceof JSONObject) {

                        SelectorInfo parsedInfo = extractFromParsedObject(prop.getJSONObject("v"));
                        if (parsedInfo != null) {
                            return parsedInfo;
                        }
                    }
                }

                // If we found a selector, return it
                if (selector != null) {
                    return new SelectorInfo(selector, type);
                }
            }
        } catch (Exception e) {
            logger.fine("Error in extractFromInfoObject: " + e.getMessage());
        }

        return null;
    }

    /**
     * Extract selector from the parsed object structure
     */
    private static SelectorInfo extractFromParsedObject(JSONObject parsedObj) {
        try {
            if (parsedObj.has("o") && parsedObj.get("o") instanceof JSONArray) {
                JSONArray properties = parsedObj.getJSONArray("o");

                // Look for parts property
                for (int i = 0; i < properties.length(); i++) {
                    JSONObject prop = properties.getJSONObject(i);

                    if (prop.has("k") && prop.getString("k").equals("parts") &&
                            prop.has("v") && prop.get("v") instanceof JSONObject) {

                        JSONObject parts = prop.getJSONObject("v");

                        if (parts.has("a") && parts.get("a") instanceof JSONArray) {
                            JSONArray partsArray = parts.getJSONArray("a");

                            // Process each part
                            for (int j = 0; j < partsArray.length(); j++) {
                                JSONObject part = partsArray.getJSONObject(j);

                                if (part.has("o") && part.get("o") instanceof JSONArray) {
                                    JSONArray partProps = part.getJSONArray("o");

                                    String selector = null;
                                    String type = "UNKNOWN";

                                    // Look for source and name properties
                                    for (int k = 0; k < partProps.length(); k++) {
                                        JSONObject partProp = partProps.getJSONObject(k);

                                        if (partProp.has("k") && partProp.getString("k").equals("source") &&
                                                partProp.has("v") && partProp.get("v") instanceof String) {
                                            selector = partProp.getString("v");
                                        }

                                        if (partProp.has("k") && partProp.getString("k").equals("name") &&
                                                partProp.has("v") && partProp.get("v") instanceof String) {
                                            type = partProp.getString("v").toUpperCase();
                                        }
                                    }

                                    // If we found a selector, return it
                                    if (selector != null) {
                                        return new SelectorInfo(selector, type);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            logger.fine("Error in extractFromParsedObject: " + e.getMessage());
        }

        return null;
    }

    /**
     * Class to hold selector information
     */
    public static class SelectorInfo {
        public final String selector;
        public final String type;

        public SelectorInfo(String selector, String type) {
            this.selector = selector;
            this.type = type;
        }

        @Override
        public String toString() {
            return type + ": " + selector;
        }
    }
}