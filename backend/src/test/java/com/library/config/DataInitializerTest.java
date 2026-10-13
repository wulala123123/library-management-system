package com.library.config;

import com.library.entity.User;
import com.library.mapper.UserMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.junit.jupiter.api.Assertions.*;

class DataInitializerTest {
    @Test void emptyDatabaseRequiresExplicitAdminCredentials() {
        UserMapper mapper=mock(UserMapper.class);
        when(mapper.selectCount(any())).thenReturn(0L);
        DataInitializer init=new DataInitializer(mapper,new BCryptPasswordEncoder());
        ReflectionTestUtils.setField(init,"username",""); ReflectionTestUtils.setField(init,"password","");
        assertThrows(IllegalStateException.class,init::run); verify(mapper,never()).insert(any(User.class));
    }
    @Test void existingAdministratorIsNeverModified() {
        UserMapper mapper=mock(UserMapper.class);when(mapper.selectCount(any())).thenReturn(1L);
        DataInitializer init=new DataInitializer(mapper,new BCryptPasswordEncoder());
        init.run(); verify(mapper,never()).updateById(any(User.class)); verify(mapper,never()).insert(any(User.class));
    }
    @Test void configuredCredentialsAreHashedOnlyForFirstAdministrator() {
        UserMapper mapper=mock(UserMapper.class);when(mapper.selectCount(any())).thenReturn(0L);
        BCryptPasswordEncoder encoder=new BCryptPasswordEncoder(); DataInitializer init=new DataInitializer(mapper,encoder);
        ReflectionTestUtils.setField(init,"username","first_admin"); ReflectionTestUtils.setField(init,"password","ExplicitFirstPassword123");
        init.run(); ArgumentCaptor<User> captured=ArgumentCaptor.forClass(User.class); verify(mapper).insert(captured.capture());
        assertTrue(encoder.matches("ExplicitFirstPassword123",captured.getValue().getPassword()));
        assertEquals("ADMIN",captured.getValue().getRole());
    }
}
