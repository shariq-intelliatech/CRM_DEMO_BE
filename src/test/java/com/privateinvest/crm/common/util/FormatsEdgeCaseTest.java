package com.privateinvest.crm.common.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Hard gate G2 (brief §2.4): "an ABN that fails the check, a wrong date, a wrong email format". The ACN examples
 * are ASIC's published check-digit examples; 51 824 753 556 is the ATO's published sample ABN.
 */
@DisplayName("Formats: ACN, ABN and email checks (hard gate G2)")
class FormatsEdgeCaseTest {

    @ParameterizedTest(name = "ACN {0} is valid")
    @ValueSource(strings = {"000 000 019", "000 250 000", "000 500 005", "000 750 005", "001 000 004", "001 250 004",
            "001 500 009", "001 749 999", "001 999 999", "002 249 998", "002 499 998", "002 749 993", "002 999 993",
            "003 249 992", "003 499 992", "003 749 988", "003 999 988", "004 249 987", "004 499 987", "004 749 982",
            "004 999 982", "005 249 981", "005 499 981", "005 749 986", "005 999 977", "006 249 976", "006 499 976",
            "006 749 980", "006 999 980", "007 249 989", "007 499 989", "007 749 975", "007 999 975", "008 249 974",
            "008 499 974", "008 749 979", "008 999 979", "009 249 969", "009 499 969", "009 749 964", "009 999 964",
            "010 249 966", "010 499 966", "010 749 961"})
    void asicExampleAcnsAreValid(String acn) {
        assertThat(Formats.isValidAcn(acn)).isTrue();
        assertThat(Formats.acnError(acn)).isNull();
    }

    @ParameterizedTest(name = "ACN \"{0}\" is invalid")
    @ValueSource(strings = {"000 000 018", "010 749 962", "12345678", "1234567890", "abc def ghi", "0000000I9"})
    void badAcnsAreInvalid(String acn) {
        assertThat(Formats.isValidAcn(acn)).isFalse();
        assertThat(Formats.acnError(acn)).isNotNull();
    }

    @Test
    @DisplayName("ACN errors say whether the length or the check digit is wrong")
    void acnErrorsExplain() {
        assertThat(Formats.acnError("12345")).isEqualTo("An ACN has 9 digits. This one has 5.");
        assertThat(Formats.acnError("000000018")).contains("check digit");
    }

    @ParameterizedTest(name = "ABN \"{0}\" is valid")
    @ValueSource(strings = {"51 824 753 556", "51824753556", "  51 824 753 556  ", "51-824-753-556"})
    void atoSampleAbnIsValidHoweverItIsTyped(String abn) {
        assertThat(Formats.isValidAbn(abn)).isTrue();
        assertThat(Formats.abnError(abn)).isNull();
    }

    @ParameterizedTest(name = "ABN \"{0}\" is invalid")
    @ValueSource(strings = {"51 824 753 557", "61 824 753 556", "5182475355", "518247535566", "00000000000"})
    void badAbnsAreInvalid(String abn) {
        assertThat(Formats.isValidAbn(abn)).isFalse();
        assertThat(Formats.abnError(abn)).isNotNull();
    }

    @Test
    @DisplayName("an ABN that wraps a valid ACN (as the ATO issues to companies) is valid")
    void companyAbnWrapsAcn() {
        // 53 004 085 616: an ABN with the ACN 004 085 616 inside.
        assertThat(Formats.isValidAcn("004 085 616")).isTrue();
        assertThat(Formats.isValidAbn("53 004 085 616")).isTrue();
    }

    @Test
    @DisplayName("ABN errors say whether the length or the checksum is wrong")
    void abnErrorsExplain() {
        assertThat(Formats.abnError("1234")).isEqualTo("An ABN has 11 digits. This one has 4.");
        assertThat(Formats.abnError("51824753557")).contains("ABN Lookup");
    }

    @ParameterizedTest(name = "email \"{0}\" is valid")
    @ValueSource(strings = {"a@b.co", "first.last@privateinvest.com.au", "name+tag@example.test", " padded@example.test "})
    void goodEmails(String email) {
        assertThat(Formats.isValidEmail(email)).isTrue();
    }

    @ParameterizedTest(name = "email \"{0}\" is invalid")
    @NullAndEmptySource
    @ValueSource(strings = {"plain", "no@dot", "@example.test", "two@@example.test", "spa ce@example.test", "a@b."})
    void badEmails(String email) {
        assertThat(Formats.isValidEmail(email)).isFalse();
    }

    @Test
    @DisplayName("ACN and ABN are shown in their usual groups; anything else is left as typed")
    void formatting() {
        assertThat(Formats.formatAcn("000000019")).isEqualTo("000 000 019");
        assertThat(Formats.formatAbn("51824753556")).isEqualTo("51 824 753 556");
        assertThat(Formats.formatAcn("123")).isEqualTo("123");
        assertThat(Formats.formatAbn("51 824")).isEqualTo("51 824");
    }

    @Test
    @DisplayName("clean() stores blanks as null so the database never holds empty strings")
    void clean() {
        assertThat(Formats.clean(null)).isNull();
        assertThat(Formats.clean("   ")).isNull();
        assertThat(Formats.clean("  x  ")).isEqualTo("x");
        assertThat(Formats.digits("+61 (02) 9000-1234")).isEqualTo("610290001234");
        assertThat(Formats.digits(null)).isEmpty();
        assertThat(Formats.isBlank(" ")).isTrue();
    }
}
