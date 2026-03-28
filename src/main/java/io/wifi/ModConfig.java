package io.wifi;

public class ModConfig {

    /**
     * MC-MultiLogin-service 的访问地址（不含末尾斜线）。
     * 格式：http(s)://host:port/method-url
     * 例如：http://127.0.0.1:25600/login_train
     */
    private String apiUrl = "http://127.0.0.1:25600/login_train";
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
