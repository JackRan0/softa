package io.softa.starter.user.constant;

import org.junit.jupiter.api.Test;

import io.softa.starter.user.entity.Role;

import static org.assertj.core.api.Assertions.assertThat;

class RoleConstantTest {

    @Test
    void isSuperAdmin_nullRole_false() {
        assertThat(RoleConstant.isSuperAdmin(null)).isFalse();
    }

    @Test
    void isSuperAdmin_nullCode_false() {
        Role r = new Role();
        assertThat(RoleConstant.isSuperAdmin(r)).isFalse();
    }

    @Test
    void isSuperAdmin_matchingCode_true() {
        Role r = new Role();
        r.setCode(RoleConstant.CODE_SUPER_ADMIN);
        assertThat(RoleConstant.isSuperAdmin(r)).isTrue();
    }

    @Test
    void isSuperAdmin_wrongCode_false() {
        Role r = new Role();
        r.setCode("HR_MANAGER");
        assertThat(RoleConstant.isSuperAdmin(r)).isFalse();
    }

    @Test
    void isSuperAdmin_caseSensitive() {
        // Reserved codes are case-sensitive — admin can't sneak in "super_admin".
        Role r = new Role();
        r.setCode("super_admin");
        assertThat(RoleConstant.isSuperAdmin(r)).isFalse();
    }
}
