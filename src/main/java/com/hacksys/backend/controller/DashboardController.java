package com.hacksys.backend.controller;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

@Controller
public class DashboardController {

    @GetMapping(value = {"/", "/index.html", "/dashboard", "/dashboard/"}, produces = MediaType.TEXT_HTML_VALUE)
    public String dashboard() {
        return "forward:/dashboard/index.html";
    }
}

