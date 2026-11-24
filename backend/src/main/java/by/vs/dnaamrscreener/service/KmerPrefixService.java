package by.vs.dnaamrscreener.service;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class KmerPrefixService {

    public List<String> indexes = new ArrayList<>(List.of("k17", "k21"));

    public String addNewIndex(String index) {
        String indexLower = index.toLowerCase();
        if (!indexes.contains(indexLower))
            indexes.add(indexLower);
        return indexLower;
    }

    public boolean deleteIndex(String index) {
        if (index.equalsIgnoreCase("k17") || index.equalsIgnoreCase("k21")) {
            return false;
        } else {
            return indexes.remove(index);
        }
    }

    public void requirePrefix(String prefix) {
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        if (prefix.contains(":") || prefix.contains(" ")) {
            throw new IllegalArgumentException("prefix must not contain ':' or space");
        }
    }

    public boolean isIndexExists(String index) {
        return indexes.contains(index.toLowerCase());
    }
}
