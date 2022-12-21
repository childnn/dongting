/*
 * Copyright The Dongting Project
 *
 * The Dongting Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package com.github.dtprj.dongting.common;

import java.util.Objects;

/**
 * @author huangli
 */
public class IntObjMap<V> {
    private static final int MAX_ARRAY_SIZE = 1 << 30;
    private int size;
    private int resizeThreshold;
    private final float loadFactor;
    private int[] keys;
    private Object[] values;

    public IntObjMap() {
        this(8, 0.75f);
    }

    public IntObjMap(int initSize, float loadFactor) {
        ObjUtil.checkPositive(initSize, "initSize");
        ObjUtil.checkPositive(loadFactor, "loadFactor");
        resizeThreshold = BitUtil.nextHighestPowerOfTwo(initSize);
        this.loadFactor = loadFactor;
    }

    protected int hashCode(int v) {
        return v;
    }

    public V get(int key) {
        return find(key, false);
    }

    private V find(int key, boolean remove) {
        int h = hashCode(key);
        Object[] values = this.values;
        if (values == null) {
            return null;
        }
        int idx = h & (values.length - 1);
        Object existData = values[idx];
        if (existData == null) {
            return null;
        }
        if (existData instanceof IntMapNode) {
            @SuppressWarnings("unchecked")
            IntMapNode<V> mn = (IntMapNode<V>) existData;
            if (mn.getKey() == key) {
                if (remove) {
                    IntMapNode<V> next = mn.getNext();
                    values[idx] = next;
                    if (next != null) {
                        keys[idx] = next.getKey();
                    } else {
                        keys[idx] = 0;
                    }
                    size--;
                }
                return mn.getValue();
            }
            IntMapNode<V> next;
            while ((next = mn.getNext()) != null) {
                if (next.getKey() == key) {
                    if (remove) {
                        mn.setNext(next.getNext());
                        size--;
                    }
                    return next.getValue();
                }
                mn = next;
            }
            return null;
        } else {
            if (this.keys[idx] == key) {
                if (remove) {
                    size--;
                    keys[idx] = 0;
                    values[idx] = null;
                }
                //noinspection unchecked
                return (V) existData;
            } else {
                return null;
            }
        }
    }

    public V put(int key, V value) {
        Objects.requireNonNull(value);
        int[] keys = resize();
        Object[] values = this.values;
        V r = put0(keys, values, key, value, keys.length - 1);
        if (r == null) {
            size++;
        }
        return r;
    }

    private V put0(int[] keys, Object[] values, int key, V value, int mask) {
        int h = hashCode(key);
        int idx = h & mask;
        Object existData = values[idx];
        if (existData == null) {
            keys[idx] = key;
            values[idx] = value;
            return null;
        } else {
            IntMapNode<V> mn;
            if (existData instanceof IntMapNode) {
                //noinspection unchecked
                mn = (IntMapNode<V>) existData;
                while (true) {
                    if (mn.getKey() == key) {
                        V old = mn.getValue();
                        mn.setValue(value);
                        return old;
                    }
                    IntMapNode<V> next = mn.getNext();
                    if (next == null) {
                        IntMapNode<V> newNode = new IntMapNode<>(key, value);
                        mn.setNext(newNode);
                        return null;
                    } else {
                        mn = next;
                    }
                }
            } else {
                if (keys[idx] == key) {
                    values[idx] = value;
                    //noinspection unchecked
                    return (V) existData;
                } else {
                    //noinspection unchecked
                    mn = new IntMapNode<>(keys[idx], (V) existData);
                    values[idx] = mn;
                    IntMapNode<V> newNode = new IntMapNode<>(key, value);
                    mn.setNext(newNode);
                    return null;
                }
            }
        }
    }

    private int[] resize() {
        int threshold = this.resizeThreshold;
        int[] oldKeys = this.keys;
        if (oldKeys == null) {
            keys = new int[threshold];
            values = new Object[threshold];
            this.resizeThreshold = (int) (loadFactor * threshold);
            return keys;
        }
        if (size < threshold) {
            return oldKeys;
        }

        int oldArrayLength = oldKeys.length;
        if (oldArrayLength >= MAX_ARRAY_SIZE) {
            return oldKeys;
        }
        int newSize = oldKeys.length << 1;
        this.resizeThreshold = (int) (loadFactor * newSize);
        int mask = newSize - 1;
        int[] newKeys = new int[newSize];
        Object[] newValues = new Object[newSize];
        Object[] values = this.values;
        int len = values.length;
        for (int i = 0; i < len; i++) {
            Object v = values[i];
            if (v == null) {
                continue;
            }
            if (v instanceof IntMapNode) {
                @SuppressWarnings("unchecked")
                IntMapNode<V> mn = (IntMapNode<V>) v;
                do {
                    put0(newKeys, newValues, mn.getKey(), mn.getValue(), mask);
                    mn = mn.getNext();
                } while (mn != null);
            } else {
                //noinspection unchecked
                put0(newKeys, newValues, keys[i], (V) v, mask);
            }
        }
        this.keys = newKeys;
        this.values = newValues;
        return newKeys;
    }

    public V remove(int key) {
        return find(key, true);
    }

    public int size() {
        return size;
    }

    public void forEach(Visitor<V> visitor) {
        int[] keys = this.keys;
        if (keys == null) {
            return;
        }
        Object[] values = this.values;
        int len = values.length;
        for (int i = 0; i < len; i++) {
            Object v = values[i];
            if (v == null) {
                continue;
            }
            if (v instanceof IntMapNode) {
                @SuppressWarnings("unchecked")
                IntMapNode<V> mn = (IntMapNode<V>) v;
                do {
                    visitor.visit(mn.getKey(), mn.getValue());
                    mn = mn.getNext();
                } while (mn != null);
            } else {
                //noinspection unchecked
                visitor.visit(keys[i], (V) v);
            }
        }
    }

    @FunctionalInterface
    public interface Visitor<V> {
        void visit(int key, V value);
    }
}
