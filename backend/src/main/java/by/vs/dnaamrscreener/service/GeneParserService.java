package by.vs.dnaamrscreener.service;

import org.springframework.stereotype.Service;

@Service
public class GeneParserService {

    public String parseGeneName(String header) {
        if (header == null || header.isBlank()) {
            return "unknown";
        }
        String h = header.trim();
        try {
            if (h.contains("|")) {
                String[] parts = h.split("\\|");
                String last = parts[parts.length - 1].trim();
                int bracket = last.indexOf('[');
                if (bracket > 0) {
                    last = last.substring(0, bracket).trim();
                }
                if (!last.isEmpty()) {
                    return last;
                }
            }
            int sp = h.indexOf(' ');
            if (sp > 0) {
                return h.substring(0, sp).trim();
            }
            return h;
        } catch (Exception e) {
            return h.length() > 64 ? h.substring(0, 64) : h;
        }
    }

    public String determineAntibioticClass(String geneId) {
        if (geneId == null || geneId.isBlank()) {
            return "unknown antibiotics";
        }

        String g = geneId.toLowerCase()
                .replace('_', '-')
                .replace(" ", "");

        if (isCarbapenemase(g)) {
            return "carbapenems";
        }
        if (isEsblOrAmpC(g)) {
            return "extended-spectrum cephalosporins";
        }
        if (g.startsWith("bla") || startsWithAny(g, "pen", "pbp")
                || containsAny(g, "mecA", "mecC".toLowerCase())) {
            if (containsAny(g, "meca", "mecc")) {
                return "beta-lactams (methicillin)";
            }
            return "beta-lactams";
        }

        if (startsWithAny(g, "aac", "ant", "aph", "aad")
                || containsAny(g, "armA".toLowerCase(), "rmt", "npmA".toLowerCase())) {
            return "aminoglycosides";
        }

        if (startsWithAny(g, "tet", "otr", "tmex")
                || containsAny(g, "tet(", "tetx")) {
            return "tetracyclines";
        }

        if (startsWithAny(g, "erm", "mph", "mef", "msr", "ere")
                || containsAny(g, "erm(", "mph(", "mef(")) {
            return "macrolides";
        }
        if (startsWithAny(g, "lnu", "inu", "lin")
                || containsAny(g, "lnu(", "inu(")) {
            return "lincosamides";
        }
        if (startsWithAny(g, "vga", "vgb", "vat", "vgh")
                || containsAny(g, "vga(", "vgb(", "vat(")) {
            return "streptogramins";
        }
        if (g.startsWith("cfr") || g.contains("cfr(")) {
            return "MLS | phenicols | oxazolidinones (cfr)";
        }

        if (startsWithAny(g, "qnr", "oqx", "qep")
                || containsAny(g, "aac(6')-ib-cr", "aac(6)-ib-cr", "aac6ibcr")
                || g.contains("gyra") || g.contains("parC".toLowerCase())) {
            return "fluoroquinolones";
        }

        if (g.startsWith("van") || containsAny(g, "vanA".toLowerCase(), "vanB".toLowerCase(),
                "vanC".toLowerCase(), "vanD".toLowerCase(), "vanM".toLowerCase())) {
            return "glycopeptides";
        }

        if (g.startsWith("mcr") || g.contains("mcr-")) {
            return "polymyxins";
        }

        if (startsWithAny(g, "sul", "dfr", "dhfr", "dhps")) {
            if (g.startsWith("dfr") || g.contains("dhfr")) {
                return "trimethoprim";
            }
            return "sulfonamides";
        }

        if (startsWithAny(g, "cat", "cml", "flo", "cmx", "fex")
                || containsAny(g, "cat(", "cml(", "floR".toLowerCase())) {
            return "phenicols";
        }

        if (startsWithAny(g, "fos", "fosA", "fosB", "fosC", "fosX")
                || containsAny(g, "fosa", "fosb", "fosc", "fosx")) {
            return "fosfomycin";
        }

        if (startsWithAny(g, "arr", "rpoB".toLowerCase()) || g.contains("arr-")) {
            return "rifampin";
        }

        if (containsAny(g, "optrA".toLowerCase(), "poxtA".toLowerCase())) {
            return "oxazolidinones";
        }

        if (startsWithAny(g, "acr", "mex", "ade", "nor", "mdt", "tolC".toLowerCase())
                || containsAny(g, "efflux", "rnd", "mate", "smr", "abc-f", "abcf")) {
            return "multidrug efflux";
        }

        if (startsWithAny(g, "qac", "sugE".toLowerCase()) || g.contains("qacE")) {
            return "biocides | quaternary ammonium";
        }

        if (startsWithAny(g, "nim")) {
            return "nitroimidazoles";
        }

        if (startsWithAny(g, "fus") || containsAny(g, "fusb", "fusc", "fusd", "fusf")) {
            return "fusidic acid";
        }

        if (startsWithAny(g, "sat") || g.contains("sat-")) {
            return "streptothricin";
        }

        if (startsWithAny(g, "ble")) {
            return "bleomycin";
        }

        return "unknown antibiotics";
    }

    private static boolean isCarbapenemase(String g) {
        if (containsAny(g, "kpc", "ndm", "vim", "imp", "ges", "nmc", "sme", "imi", "dim",
                "gim", "sim", "spm", "fim", "aim", "tmb", "sfh")) {
            return true;
        }
        if (g.contains("oxa")) {
            return containsAny(g,
                    "oxa-48", "oxa48", "oxa-181", "oxa181", "oxa-232", "oxa232",
                    "oxa-244", "oxa244", "oxa-162", "oxa162", "oxa-204", "oxa204",
                    "oxa-23", "oxa23", "oxa-24", "oxa24", "oxa-40", "oxa40",
                    "oxa-58", "oxa58", "oxa-143", "oxa143", "oxa-235", "oxa235",
                    "oxa-51", "oxa51");
        }
        return false;
    }

    private static boolean isEsblOrAmpC(String g) {
        return containsAny(g,
                "ctx-m", "ctxm", "ctx",
                "shv", "tem", "oxy", "per", "bel", "rth", "veb", "ges",
                "cmy", "dha", "fox", "mox", "acc", "act", "mir", "lat", "ent",
                "ampC".toLowerCase());
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) {
            if (s.contains(n.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    private static boolean startsWithAny(String s, String... prefixes) {
        for (String p : prefixes) {
            if (s.startsWith(p.toLowerCase())) {
                return true;
            }
        }
        return false;
    }
}