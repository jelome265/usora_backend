package com.usora.notification.service;

import org.springframework.beans.BeansException;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

@Component
public class ApplicationContextProvider implements ApplicationContextAware {

    private static ApplicationContext context;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        context = applicationContext;
    }

    @SuppressWarnings("unchecked")
    public static <T> T getBean(Class<T> beanClass) {
        return context.getBean(beanClass);
    }

    @SuppressWarnings("unchecked")
    public static <T> T getBean(String name, Class<T> beanClass) {
        return context.getBean(name, beanClass);
    }
}
