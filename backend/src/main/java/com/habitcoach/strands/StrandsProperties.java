package com.habitcoach.strands;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "strands")
public class StrandsProperties {

    /** Base URL of the Python Strands service, e.g. http://localhost:8900 */
    private String baseUrl = "http://localhost:8900";

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }
}
