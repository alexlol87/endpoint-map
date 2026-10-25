package com.acme.profile;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("user_profiles")
public record UserProfile(@Id Long id, String userId, String email) {
    public UserProfile withEmail(String newEmail) {
        return new UserProfile(id, userId, newEmail);
    }
}
