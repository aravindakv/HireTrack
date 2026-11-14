package com.aravindakv.hiretrack.common.web;

import org.springframework.context.annotation.Import;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Put on every servlet service's @SpringBootApplication class. */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Import(HiretrackSecurityConfiguration.class)
public @interface EnableHiretrackSecurity {}