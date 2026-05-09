package com.hacksys.backend.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

@Controller
public class DashboardController {

    @Value("classpath:static/dashboard/index.html")
    private Resource htmlResource;

    @GetMapping({"/", "/index.html", "/dashboard", "/dashboard/"})
    @ResponseBody
    public String dashboard() throws IOException {
        return new String(htmlResource.getInputStream().readAllBytes());
    }
}

