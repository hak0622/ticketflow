package studying.blog.exception;
public class PaymentDeferredException extends RuntimeException { public PaymentDeferredException(){super("Payment result unresolved; ticket release deferred");} }
