package by.vs.dnaamrscreener.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class GeneParserServiceTest {

    private GeneParserService parser;

    @BeforeEach
    void setUp() {
        parser = new GeneParserService();
    }

    @Nested
    @DisplayName("parseGeneName")
    class ParseGeneName {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"  ", "\t"})
        void blank_returns_unknown(String header) {
            assertEquals("unknown", parser.parseGeneName(header));
        }

        @Test
        void card_style_pipe_header() {
            String h = "gb|NG_047482.1|+|blaNDM-1|NDM-1 [Klebsiella pneumoniae]";
            assertEquals("NDM-1", parser.parseGeneName(h));
        }

        @Test
        void pipe_header_without_bracket() {
            assertEquals("blaKPC-2", parser.parseGeneName("ARO:300001 | blaKPC-2"));
        }

        @Test
        void simple_space_separated() {
            assertEquals("mecA", parser.parseGeneName("mecA methicillin resistance"));
        }

        @Test
        void single_token() {
            assertEquals("tetA", parser.parseGeneName("tetA"));
        }
    }

    @Nested
    @DisplayName("determineAntibioticClass")
    class DetermineAntibioticClass {

        @ParameterizedTest
        @NullAndEmptySource
        void blank_returns_unknown(String geneId) {
            assertEquals("unknown antibiotics", parser.determineAntibioticClass(geneId));
        }

        @ParameterizedTest
        @CsvSource({
                "blaNDM-1, carbapenems",
                "blaKPC-2, carbapenems",
                "blaOXA-48, carbapenems",
                "blaVIM-1, carbapenems",
                "blaCTX-M-15, extended-spectrum cephalosporins",
                "blaTEM-1, extended-spectrum cephalosporins",
                "blaSHV-12, extended-spectrum cephalosporins",
                "mecA, beta-lactams (methicillin)",
                "blaZ, beta-lactams",
                "aac(6')-Ib, aminoglycosides",
                "aph(3')-Ia, aminoglycosides",
                "tetA, tetracyclines",
                "tet(X), tetracyclines",
                "ermB, macrolides",
                "mphA, macrolides",
                "qnrS1, fluoroquinolones",
                "vanA, glycopeptides",
                "mcr-1, polymyxins",
                "sul1, sulfonamides",
                "dfrA1, trimethoprim",
                "catA1, phenicols",
                "fosA3, fosfomycin",
                "arr-2, rifampin",
                "optrA, oxazolidinones",
                "qacEdelta1, biocides | quaternary ammonium",
                "randomGeneXYZ, unknown antibiotics"
        })
        void maps_known_genes(String geneId, String expectedClass) {
            assertEquals(expectedClass, parser.determineAntibioticClass(geneId));
        }

        @Test
        void underscores_normalized() {
            assertEquals("carbapenems", parser.determineAntibioticClass("bla_NDM_1"));
        }
    }
}
