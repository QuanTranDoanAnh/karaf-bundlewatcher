package vn.quantda.example.osgi.bundlewatcher.collections;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * A wrapper around a dictionary access it as a Map
 */
public class DictionaryAsMap<U, V> extends AbstractMap<U, V>
{

    private Dictionary<U, V> dict;

    public DictionaryAsMap(Dictionary<U, V> dict)
    {
        this.dict = dict;
    }

    @Override
    public Set<Entry<U, V>> entrySet()
    {
        return new AbstractSet<Entry<U, V>>()
        {
            @Override
            public Iterator<Entry<U, V>> iterator()
            {
                final Enumeration<U> e = dict.keys();
                return new Iterator<Entry<U, V>>()
                {
                    private U key;
                    public boolean hasNext()
                    {
                        return e.hasMoreElements();
                    }

                    public Entry<U, V> next()
                    {
                        key = e.nextElement();
                        return new KeyEntry(key);
                    }

                    public void remove()
                    {
                        if (key == null)
                        {
                            throw new IllegalStateException();
                        }
                        dict.remove(key);
                    }
                };
            }

            @Override
            public int size()
            {
                return dict.size();
            }
        };
    }

    @Override
    public V put(U key, V value) {
        return dict.put(key, value);
    }

    class KeyEntry implements Map.Entry<U,V> {

        private final U key;

        KeyEntry(U key) {
            this.key = key;
        }

        public U getKey() {
            return key;
        }

        public V getValue() {
            return dict.get(key);
        }

        public V setValue(V value) {
            return DictionaryAsMap.this.put(key, value);
        }
    }

}
