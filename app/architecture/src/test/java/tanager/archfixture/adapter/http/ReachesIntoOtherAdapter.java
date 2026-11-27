package tanager.archfixture.adapter.http;

import tanager.archfixture.adapter.sql.SqlStore;

/** 위반 픽스처: 어댑터가 다른 어댑터를 직접 쓴다. */
public class ReachesIntoOtherAdapter {
    SqlStore store;
}
