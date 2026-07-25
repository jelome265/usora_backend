package com.usora.notification.unit;

import com.usora.notification.mapper.EntityMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MapperUnitTest {
    @Autowired
    private EntityMapper entityMapper;
    
    @Test
    void contextLoads() {
        assertNotNull(entityMapper);
    }
}
