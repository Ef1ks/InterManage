package com.intermanage.backend.product.dto;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.intermanage.backend.product.entity.ArticleStoreLink;

/**
 * Połączone artykuły sklepów pokazanych w raporcie, zebrane w grupy (połączenia łańcuchowe też: A↔B i B↔C to jedna
 * grupa). Kluczem wiersza raportu jest ITM8 członka grupy ze sklepu, który stoi w raporcie najwcześniej, więc w
 * raporcie lady wiersz ma ITM8 bieżącego sklepu. Artykuł bez połączeń ma klucz = swój ITM8, czyli jak dotąd ten sam
 * ITM8 w różnych sklepach to jeden wiersz.
 */
public final class ArticleLinks {

    private static final ArticleLinks NONE = new ArticleLinks(Map.of(), Map.of(), Map.of());

    /** Członek grupy ("sklep|ITM8") → klucz grupy. */
    private final Map<String, String> keys;
    /** "klucz|sklep" → ITM8 członka grupy w tym sklepie. */
    private final Map<String, String> members;
    /** Członek grupy → id połączenia, które go do niej dołącza (do odłączenia). */
    private final Map<String, UUID> linkIds;

    private ArticleLinks(Map<String, String> keys, Map<String, String> members, Map<String, UUID> linkIds) {
        this.keys = keys;
        this.members = members;
        this.linkIds = linkIds;
    }

    public static ArticleLinks none() {
        return NONE;
    }

    /** {@code stores} w kolejności raportu: pierwszy sklep daje klucz grupy. */
    public static ArticleLinks of(List<ArticleStoreLink> links, List<UUID> stores) {
        if (links.isEmpty()) {
            return NONE;
        }
        Map<String, String> parent = new HashMap<>();
        Map<String, UUID> linkIds = new HashMap<>();
        for (ArticleStoreLink link : links) {
            String a = node(link.getStoreId(), link.getItm8());
            String b = node(link.getLinkedStoreId(), link.getLinkedItm8());
            parent.putIfAbsent(a, a);
            parent.putIfAbsent(b, b);
            union(parent, a, b);
            linkIds.putIfAbsent(a, link.getId());
            linkIds.putIfAbsent(b, link.getId());
        }
        Map<String, String> best = new HashMap<>();
        for (String node : parent.keySet()) {
            String root = find(parent, node);
            String current = best.get(root);
            if (current == null || before(node, current, stores)) {
                best.put(root, node);
            }
        }
        Map<String, String> keys = new HashMap<>();
        Map<String, String> members = new HashMap<>();
        for (String node : parent.keySet()) {
            String key = itm8Of(best.get(find(parent, node)));
            keys.put(node, key);
            members.putIfAbsent(key + "|" + storeOf(node), itm8Of(node));
        }
        return new ArticleLinks(keys, members, linkIds);
    }

    /** Klucz wiersza raportu dla artykułu sklepu. */
    public String key(UUID storeId, String itm8) {
        if (itm8 == null) {
            return null;
        }
        return keys.getOrDefault(node(storeId, itm8), itm8);
    }

    /** ITM8 artykułu sklepu połączonego z wierszem {@code key}; bez połączenia {@code null}. */
    public String member(String key, UUID storeId) {
        return members.get(key + "|" + storeId);
    }

    /** Połączenie, które dołącza artykuł sklepu do wiersza; bez połączenia {@code null}. */
    public UUID linkId(UUID storeId, String itm8) {
        return itm8 == null ? null : linkIds.get(node(storeId, itm8));
    }

    private static boolean before(String node, String other, List<UUID> stores) {
        int a = stores.indexOf(UUID.fromString(storeOf(node)));
        int b = stores.indexOf(UUID.fromString(storeOf(other)));
        if (a != b) {
            return a >= 0 && (b < 0 || a < b);
        }
        return itm8Of(node).compareTo(itm8Of(other)) < 0;
    }

    private static String node(UUID storeId, String itm8) {
        return storeId + "|" + itm8;
    }

    private static String storeOf(String node) {
        return node.substring(0, node.indexOf('|'));
    }

    private static String itm8Of(String node) {
        return node.substring(node.indexOf('|') + 1);
    }

    private static String find(Map<String, String> parent, String node) {
        String root = node;
        while (!parent.get(root).equals(root)) {
            root = parent.get(root);
        }
        parent.put(node, root);
        return root;
    }

    private static void union(Map<String, String> parent, String a, String b) {
        String rootA = find(parent, a);
        String rootB = find(parent, b);
        if (!rootA.equals(rootB)) {
            parent.put(rootB, rootA);
        }
    }
}
