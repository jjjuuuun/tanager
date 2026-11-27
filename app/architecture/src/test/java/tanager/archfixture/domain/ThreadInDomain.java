package tanager.archfixture.domain;

/** 위반 픽스처: domain 이 스레드를 직접 만든다. */
public class ThreadInDomain {
    Thread worker = new Thread(() -> {});
}
