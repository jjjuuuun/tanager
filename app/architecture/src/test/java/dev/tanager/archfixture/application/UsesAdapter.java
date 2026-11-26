package dev.tanager.archfixture.application;

import dev.tanager.archfixture.adapter.sql.SqlStore;

/** 위반 픽스처: application 이 어댑터 구현을 직접 쓴다(포트를 거쳐야 함). */
public class UsesAdapter {
    SqlStore store;
}
