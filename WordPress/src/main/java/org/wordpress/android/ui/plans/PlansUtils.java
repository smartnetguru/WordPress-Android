package org.wordpress.android.ui.plans;

import android.support.annotation.Nullable;
import android.text.TextUtils;

import com.android.volley.VolleyError;
import com.wordpress.rest.RestRequest;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.wordpress.android.WordPress;
import org.wordpress.android.models.Blog;
import org.wordpress.android.ui.plans.models.Feature;
import org.wordpress.android.ui.plans.models.Plan;
import org.wordpress.android.ui.plans.models.SitePlan;
import org.wordpress.android.ui.prefs.AppPrefs;
import org.wordpress.android.util.AppLog;

import java.util.ArrayList;
import java.util.Currency;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class PlansUtils {

    public static final String deviceCurrencyCode; // ISO 4217 currency code.
    public static final String deviceCurrencySymbol;

    public static final String DOLLAR_SYMBOL = "$";
    public static final String DOLLAR_ISO4217_CODE = "USD";

    static {
        Currency currency = Currency.getInstance(Locale.getDefault());
        deviceCurrencyCode = currency.getCurrencyCode();
        deviceCurrencySymbol = currency.getSymbol(Locale.getDefault());
    }

    public static int getPlanPriceValue(Plan plan) {
        Hashtable<String, Integer> pricesMap = plan.getPrices();
        if (pricesMap.containsKey(deviceCurrencyCode)) {
            return pricesMap.get(deviceCurrencyCode);
        }
        // Returns US dollars price
        return pricesMap.get(DOLLAR_ISO4217_CODE);
    }

    public static String getPlanPriceCurrencySymbol(Plan plan) {
        Hashtable<String, Integer> pricesMap = plan.getPrices();
        if (pricesMap.containsKey(deviceCurrencyCode)) {
           return deviceCurrencySymbol;
        }

        // Returns US dollars symbol
        return DOLLAR_SYMBOL;
    }

    public interface AvailablePlansListener {
        void onResponse(List<SitePlan> plans);
        void onError();
    }

    public static boolean downloadAvailablePlansForSite(int localTableBlogID, final AvailablePlansListener listener) {
        final Blog blog = WordPress.getBlog(localTableBlogID);
        if (blog == null || !isPlanFeatureAvailableForBlog(blog)) {
            return false;
        }

        Map<String, String> params = getDefaultRestCallParameters();
        WordPress.getRestClientUtils().get("sites/" + blog.getDotComBlogId() + "/plans", params, null, new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject response) {
                if (response == null) {
                    AppLog.w(AppLog.T.PLANS, "Unexpected empty response from server");
                    if (listener != null) {
                        listener.onError();
                    }
                    return;
                }

                AppLog.d(AppLog.T.PLANS, response.toString());
                List<SitePlan> plans = new ArrayList<>();
                try {
                    JSONArray planIDs = response.names();
                    if (planIDs != null) {
                        for (int i = 0; i < planIDs.length(); i++) {
                            String currentKey = planIDs.getString(i);
                            JSONObject currentPlanJSON = response.getJSONObject(currentKey);
                            SitePlan currentPlan = new SitePlan(Long.valueOf(currentKey), currentPlanJSON, blog);
                            plans.add(currentPlan);
                        }
                    }
                    if (listener != null) {
                        listener.onResponse(plans);
                    }
                } catch (JSONException e) {
                    AppLog.e(AppLog.T.PLANS, "Can't parse the plans list returned from the server", e);
                    if (listener != null) {
                        listener.onError();
                    }
                }
            }
        }, new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.UTILS, "Error downloading site plans for the site with ID " + blog.getDotComBlogId(), volleyError);
                if (listener != null) {
                    listener.onError();
                }
            }
        });

        return true;
    }

    @Nullable
    public static Plan getGlobalPlan(long planId) {
        List<Plan> plans = getGlobalPlans();
        if (plans == null || plans.size() == 0) {
            return null;
        }

        for (Plan current: plans) {
            if (current.getProductID() == planId) {
                return  current;
            }
        }

        return null;
    }

    @Nullable
    public static List<Plan> getGlobalPlans() {
        String plansString = AppPrefs.getGlobalPlans();
        if (TextUtils.isEmpty(plansString)) {
            return null;
        }

        List<Plan> plans = new ArrayList<>();
        try {
            JSONObject plansJSONObject = new JSONObject(plansString);
            JSONArray plansArray = plansJSONObject.getJSONArray("originalResponse");
            for (int i=0; i < plansArray.length(); i ++) {
                JSONObject currentPlanJSON = plansArray.getJSONObject(i);
                Plan currentPlan = new Plan(currentPlanJSON);
                plans.add(currentPlan);
            }
        } catch (JSONException e) {
            AppLog.e(AppLog.T.PLANS, "Can't parse the plans list returned from the server", e);
            return null;
        }

        return plans;
    }

    @Nullable
    public static List<Long> getGlobalPlansIDS() {
        List<Plan> plans = getGlobalPlans();
        if (plans == null) {
            return null;
        }

        List<Long> plansIDS = new ArrayList<>(plans.size());
        for (Plan currentPlan: plans) {
            plansIDS.add(currentPlan.getProductID());
        }

        return plansIDS;
    }

    @Nullable
    public static List<Feature> getFeatures() {
        String featuresString = AppPrefs.getGlobalPlansFeatures();
        if (TextUtils.isEmpty(featuresString)) {
            return null;
        }

        List<Long> plansIDS = getGlobalPlansIDS();
        if (plansIDS == null || plansIDS.size() == 0) {
            //no plans stored in the app. Features are attached to plans. We can probably returns null here.
            //TODO: Check if we need to return null or features with empty links to plans
            return null;
        }

        List<Feature> features = new ArrayList<>();
        try {
            JSONObject featuresJSONObject = new JSONObject(featuresString);
            JSONArray featuresArray = featuresJSONObject.getJSONArray("originalResponse");
            for (int i=0; i < featuresArray.length(); i ++) {
                JSONObject currentFeatureJSON = featuresArray.getJSONObject(i);
                Feature currentFeature = new Feature(currentFeatureJSON, plansIDS);
                features.add(currentFeature);
            }
        } catch (JSONException e) {
            AppLog.e(AppLog.T.PLANS, "Can't parse the features list returned from the server", e);
            return null;
        }

        return features;
    }

    @Nullable
    public static List<Feature> getPlanFeatures(long planID) {
        List<Feature> allFeatures = getFeatures();
        if (allFeatures == null) {
            return null;
        }

        List<Feature> currentPlanFeatures = new ArrayList<>();
        for (Feature currentFeature : allFeatures) {
            if (currentFeature.getPlanIDToDescription().containsKey(planID)) {
                currentPlanFeatures.add(currentFeature);
            }
        }

        return  currentPlanFeatures;
    }

    public static boolean downloadGlobalPlans() {
        Map<String, String> params = getDefaultRestCallParameters();
        WordPress.getRestClientUtilsV1_2().get("plans/", params, null, new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject response) {
                if (response != null) {
                    AppLog.d(AppLog.T.PLANS, response.toString());
                    // Store the response into App Prefs
                    AppPrefs.setGlobalPlans(response.toString());

                    // Load details of features from the server.
                    downloadFeatures();
                } else {
                    AppLog.w(AppLog.T.PLANS, "Empty response downloading global Plans!");
                }
            }
        }, new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.PLANS, "Error loading plans/", volleyError);
            }
        });
        return true;
    }

    /*
     * Download Features from the WordPress.com backend.
     *
     * Return true if the request is enqueued. False otherwise.
     */
    private static boolean downloadFeatures() {
        Map<String, String> params = getDefaultRestCallParameters();
        WordPress.getRestClientUtils().get("plans/features/", params, null, new RestRequest.Listener() {
            @Override
            public void onResponse(JSONObject response) {
                if (response != null) {
                    AppLog.d(AppLog.T.PLANS, response.toString());
                    // Store the response into App Prefs
                    AppPrefs.setGlobalPlansFeatures(response.toString());
                } else {
                    AppLog.w(AppLog.T.PLANS, "Unexpected empty response from server when downloading Features!");
                }
            }
        }, new RestRequest.ErrorListener() {
            @Override
            public void onErrorResponse(VolleyError volleyError) {
                AppLog.e(AppLog.T.PLANS, "Error Loading Plans/Features", volleyError);
            }
        });

        return true;
    }

    /**
     * This function returns default parameters used in all REST Calls in Plans.
     *
     * The "locale" parameter fox example is one of those we need to add to the request. It must be set to retrieve
     * the localized version of plans descriptions and avoid hardcode them in code.
     *
     * @return The map with default parameters.
     */
    private static Map<String, String> getDefaultRestCallParameters() {
        String deviceLanguageCode = Locale.getDefault().getLanguage();
        Map<String, String> params = new HashMap<>();
        if (!TextUtils.isEmpty(deviceLanguageCode)) {
            params.put("locale", deviceLanguageCode);
        }
        return params;
    }

    /**
     * This function return true if Plans are available for a blog.
     * Basically this means that Plans data were downloaded from the server,
     * and the blog has the product_id stored in the DB.
     *
     * @param blog to test
     * @return True if Plans are enabled on the blog
     */
    public static boolean isPlanFeatureAvailableForBlog(Blog blog) {
        return !TextUtils.isEmpty(AppPrefs.getGlobalPlans()) &&
                blog != null && blog.getPlanID() != 0;
    }
}