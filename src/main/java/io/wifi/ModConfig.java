package io.wifi;

public class ModConfig {

    private String apiUrl = "";
    private boolean forceNoProxy = false;

    public String getApiUrl() {
        return apiUrl;
    }

    public boolean getForceNoProxy() {
        return forceNoProxy;
    }

    public void setApiUrl(String apiUrl) {
        this.apiUrl = apiUrl;
    }
}
