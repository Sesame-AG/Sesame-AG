package io.github.aoguai.sesameag;

oneway interface IStoreListener {
    void onChanged(int collection, String owner, String name);
}
