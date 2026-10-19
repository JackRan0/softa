package io.softa.starter.message.mail.entity;

import java.lang.reflect.Field;
import java.util.List;
import org.junit.jupiter.api.Test;

import io.softa.framework.orm.annotation.Model;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The send log is where "this user never got the email / the code" is looked into, and the person
 * doing it types the recipient's address into the list's search box. Without a declaration the model
 * — no field called {@code name} — is searched by {@code id}, and the address finds nothing. Reads the
 * annotation so it needs no context: the declaration is what is being protected.
 */
class MailSendRecordSearchNameTest {

    @Test
    void theLogIsSearchedByRecipientAndSubject() {
        assertThat(MailSendRecord.class.getAnnotation(Model.class).searchName())
                .containsExactly("toAddresses", "subject");
    }

    /** Boot refuses anything but String / MultiString here, so a retype should fail a test first. */
    @Test
    void everySearchColumnIsTextOrAListOfText() throws NoSuchFieldException {
        for (String fieldName : MailSendRecord.class.getAnnotation(Model.class).searchName()) {
            Field field = MailSendRecord.class.getDeclaredField(fieldName);
            assertThat(field.getType()).as("searchName column `%s`", fieldName).isIn(String.class, List.class);
        }
    }
}
