package com.optionstrader;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/** Explicit UI routes only: missing API and asset routes must keep their real 404 response. */
@Controller
public class WorkspaceController {
    @GetMapping({"/workspace", "/workspace/", "/workspace/overview", "/workspace/automation",
        "/workspace/audit", "/workspace/news", "/workspace/analysis", "/workspace/reports", "/workspace/positions", "/workspace/risk", "/workspace/tools",
        "/workspace/reports/{id:[1-9][0-9]{0,14}}", "/workspace/positions/{id:[A-Za-z0-9_-]{1,100}}",
        "/paper-workspace.html", "/research-tools.html"})
    public String workspace() { return "forward:/index.html"; }
}
