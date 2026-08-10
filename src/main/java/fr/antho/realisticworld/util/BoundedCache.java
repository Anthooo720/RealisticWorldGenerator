package fr.antho.realisticworld.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Function;

/**
 * Cache borné concurrent. Contrairement à l'ancien LRU synchronisé, une tuile coûteuse
 * n'empêche pas la construction d'une autre tuile indépendante sur un thread Paper voisin.
 * L'éviction est FIFO approximative : plus prévisible et beaucoup moins contentieuse.
 */
public final class BoundedCache<K,V> {
    private final int maxSize;
    private final ConcurrentHashMap<K,V> map=new ConcurrentHashMap<>();
    private final ConcurrentLinkedQueue<K> insertionOrder=new ConcurrentLinkedQueue<>();

    public BoundedCache(int maxSize){ this.maxSize=Math.max(4,maxSize); }

    public V computeIfAbsent(K key, Function<K,V> factory) {
        V existing=map.get(key);
        if(existing!=null) return existing;
        V value=map.computeIfAbsent(key,k->{
            V built=factory.apply(k);
            insertionOrder.offer(k);
            return built;
        });
        trim();
        return value;
    }

    private void trim(){
        while(map.size()>maxSize){
            K oldest=insertionOrder.poll();
            if(oldest==null) break;
            map.remove(oldest);
        }
    }

    public int size(){ return map.size(); }
}
