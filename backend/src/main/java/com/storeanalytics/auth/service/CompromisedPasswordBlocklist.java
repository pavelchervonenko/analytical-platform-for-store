package com.storeanalytics.auth.service;

@FunctionalInterface
public interface CompromisedPasswordBlocklist {

    boolean contains(String canonicalPassword);
}
