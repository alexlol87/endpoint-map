package l; @org.springframework.stereotype.Component public class Facade { private final Pay pay; public Facade(Pay pay){this.pay=pay;} public void go(){ pay.pay().block(); } }
