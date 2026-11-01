package gitops.demo.web;

import jakarta.enterprise.context.RequestScoped;

@RequestScoped
public class CorrelationIdContext {

    private String id;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }
}
