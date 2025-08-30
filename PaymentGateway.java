
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

// ======= Domain Enums & Records =======
enum Status { PENDING, SUCCESS, FAILED }

enum Currency { INR, USD }

record IdempotencyKey(String value) { public IdempotencyKey { Objects.requireNonNull(value); } }

record PaymentResult(String transactionId, Status status, double chargedAmount, String message) {}
record RefundResult(String refundId, Status status, double refundedAmount, String message) {}

record Receipt(String transactionId, String userId, String method, String maskedInfo,
               double amount, double chargedAmount, double fee, double discount,
               Status status, Instant createdAt) {
    @Override public String toString(){
        return "["+transactionId+" " + status + " " + formatMoney(chargedAmount) +
               "] " + method + "(" + maskedInfo + ")";
    }
    private static String formatMoney(double v){ return String.format(Locale.US, "\u20B9%.2f", v); }
}

// ======= Notifier =======
interface Notifier { void notify(String userId, Receipt receipt); }
final class EmailNotifier implements Notifier {
    @Override public void notify(String userId, Receipt r){
        System.out.println("[Email to "+userId+"] " + r);
    }
}
final class SMSNotifier implements Notifier {
    @Override public void notify(String userId, Receipt r){
        System.out.println("[SMS to "+userId+"] " + r);
    }
}

// ======= Repository & Transaction Model =======
final class Transaction {
    final String id;
    final String userId;
    final String methodName;
    final Currency currency;
    final double originalAmount; // pre-discount
    double capturedAmount;       // what was actually charged
    double totalRefunded;
    Status status;
    final String maskedInfo;
    final Instant createdAt;
    final IdempotencyKey key; // may be null for non-idempotent ops

    Transaction(String id, String userId, String methodName, Currency currency,
                double originalAmount, double capturedAmount, String maskedInfo,
                Status status, Instant createdAt, IdempotencyKey key){
        this.id = id; this.userId = userId; this.methodName = methodName; this.currency = currency;
        this.originalAmount = originalAmount; this.capturedAmount = capturedAmount;
        this.totalRefunded = 0.0; this.status = status; this.maskedInfo = maskedInfo;
        this.createdAt = createdAt; this.key = key;
    }
}

interface TransactionRepository {
    void save(Transaction tx);
    Optional<Transaction> findById(String id);
    Optional<Transaction> findByIdempotency(IdempotencyKey key);
    List<Transaction> findByUser(String userId);
}

final class InMemoryTransactionRepository implements TransactionRepository {
    private final Map<String, Transaction> byId = new ConcurrentHashMap<>();
    private final Map<String, Transaction> byIdem = new ConcurrentHashMap<>();
    private final Map<String, List<Transaction>> byUser = new ConcurrentHashMap<>();

    @Override public void save(Transaction tx){
        byId.put(tx.id, tx);
        if(tx.key != null) byIdem.put(tx.key.value(), tx);
        byUser.computeIfAbsent(tx.userId, k -> new ArrayList<>()).add(tx);
    }
    @Override public Optional<Transaction> findById(String id){ return Optional.ofNullable(byId.get(id)); }
    @Override public Optional<Transaction> findByIdempotency(IdempotencyKey key){
        return Optional.ofNullable(byIdem.get(key.value()));
    }
    @Override public List<Transaction> findByUser(String userId){
        return Collections.unmodifiableList(byUser.getOrDefault(userId, List.of()));
    }
}

// ======= Fee & Promo Strategies =======
interface FeeStrategy { double apply(double amountAfterDiscount, Payment payment); }

final class RegistryFeeStrategy implements FeeStrategy {
    private final Map<Class<? extends Payment>, Function<Double, Double>> fees = new HashMap<>();
    public <T extends Payment> void register(Class<T> clazz, double percent){
        fees.put(clazz, amt -> amt * percent / 100.0);
    }
    @Override public double apply(double base, Payment p){
        var fn = fees.getOrDefault(p.getClass(), amt -> 0.0);
        return round2(fn.apply(base));
    }
    static double round2(double v){ return Math.round(v * 100.0)/100.0; }
}

interface Promo { double apply(double amount); }
final class NoPromo implements Promo { public double apply(double amount){ return amount; } }
final class FlatPromo implements Promo {
    private final double flat; public FlatPromo(double flat){ this.flat = flat; }
    public double apply(double amount){ return Math.max(0.0, amount - flat); }
}
final class PercentagePromo implements Promo {
    private final double pct; public PercentagePromo(double pct){ this.pct = pct; }
    public double apply(double amount){ return amount * (1.0 - pct/100.0); }
}

// ======= Payment Abstraction =======
abstract class Payment {
    protected final String transactionId;
    protected final double amount;
    protected final Currency currency;
    protected final String userId;

    protected Payment(String transactionId, double amount, Currency currency, String userId){
        this.transactionId = Objects.requireNonNull(transactionId);
        if(amount <= 0) throw new IllegalArgumentException("Amount must be > 0");
        this.amount = round2(amount);
        this.currency = Objects.requireNonNull(currency);
        this.userId = Objects.requireNonNull(userId);
    }

    public abstract PaymentResult process(IdempotencyKey key, PaymentProcessor.Context ctx);
    public abstract RefundResult refund(double amountToRefund, PaymentProcessor.Context ctx);
    public abstract String getMaskedInfo();
    public abstract String methodName();

    protected static double round2(double v){ return Math.round(v * 100.0)/100.0; }
}

final class CreditCardPayment extends Payment {
    private final String cardHolder;
    private final String cardNumber; // never exposed raw
    private final YearMonth expiry;
    private final String cvv;

    CreditCardPayment(String transactionId, double amount, Currency currency, String userId,
                      String cardHolder, String cardNumber, YearMonth expiry, String cvv){
        super(transactionId, amount, currency, userId);
        this.cardHolder = Objects.requireNonNull(cardHolder);
        this.cardNumber = validateCard(cardNumber);
        this.expiry = Objects.requireNonNull(expiry);
        if(expiry.isBefore(YearMonth.from(LocalDate.now())))
            throw new IllegalArgumentException("Card expired");
        if(cvv == null || cvv.length() < 3 || cvv.length() > 4)
            throw new IllegalArgumentException("Invalid CVV");
        this.cvv = cvv;
    }

    private static String validateCard(String number){
        Objects.requireNonNull(number);
        String digits = number.replaceAll("\\s+", "");
        if(digits.length() < 12 || !luhnValid(digits))
            throw new IllegalArgumentException("Invalid card number");
        return digits;
    }

    private static boolean luhnValid(String n){
        int sum = 0; boolean alt = false;
        for(int i = n.length()-1; i >=0; i--){
            int d = n.charAt(i)-'0';
            if(alt){ d*=2; if(d>9) d-=9; }
            sum += d; alt = !alt;
        }
        return sum % 10 == 0;
    }

    @Override public PaymentResult process(IdempotencyKey key, PaymentProcessor.Context ctx){
        // Idempotency handled in processor/repo before delegate is called; still simulate provider
        if(providerHiccup()) return new PaymentResult(transactionId, Status.FAILED, 0.0,
                "Bank authorization timeout");

        double discounted = ctx.promo.apply(amount);
        double fee = ctx.fees.apply(discounted, this);
        double charged = round2(discounted + fee);

        ctx.persistSuccess(this, charged, fee, amount - discounted, key);
        ctx.notify(this, charged, fee, amount - discounted, Status.SUCCESS);
        return new PaymentResult(transactionId, Status.SUCCESS, charged, "Authorized");
    }

    @Override public RefundResult refund(double amt, PaymentProcessor.Context ctx){
        return ctx.performRefund(transactionId, amt, userId);
    }

    @Override public String getMaskedInfo(){
        String last4 = cardNumber.substring(cardNumber.length()-4);
        return "**** **** **** " + last4;
    }

    @Override public String methodName(){ return "Card"; }
}

final class UPIPayment extends Payment {
    private final String upiId; // never expose raw
    private static final String UPI_REGEX = "^[a-zA-Z0-9.\\-_]+@[a-zA-Z]+$";

    UPIPayment(String transactionId, double amount, Currency currency, String userId, String upiId){
        super(transactionId, amount, currency, userId);
        if(upiId == null || !upiId.matches(UPI_REGEX))
            throw new IllegalArgumentException("Invalid UPI Id");
        this.upiId = upiId;
    }

    @Override public PaymentResult process(IdempotencyKey key, PaymentProcessor.Context ctx){
        if(providerHiccup()) return new PaymentResult(transactionId, Status.FAILED, 0.0,
                "UPI provider error");
        double discounted = ctx.promo.apply(amount);
        double fee = ctx.fees.apply(discounted, this);
        double charged = round2(discounted + fee);
        ctx.persistSuccess(this, charged, fee, amount - discounted, key);
        ctx.notify(this, charged, fee, amount - discounted, Status.SUCCESS);
        return new PaymentResult(transactionId, Status.SUCCESS, charged, "Collected via UPI");
    }

    @Override public RefundResult refund(double amt, PaymentProcessor.Context ctx){
        return ctx.performRefund(transactionId, amt, userId);
    }

    @Override public String getMaskedInfo(){
        int at = upiId.indexOf('@');
        String bank = upiId.substring(at+1);
        return "****@" + bank;
    }

    @Override public String methodName(){ return "UPI"; }
}

final class WalletPayment extends Payment {
    private final String walletId;
    private static final Map<String, Double> WALLET_BALANCES = new ConcurrentHashMap<>();

    public static void topUp(String walletId, double amount){
        WALLET_BALANCES.merge(walletId, amount, Double::sum);
    }

    WalletPayment(String transactionId, double amount, Currency currency, String userId, String walletId){
        super(transactionId, amount, currency, userId);
        if(walletId == null || walletId.isBlank()) throw new IllegalArgumentException("Invalid walletId");
        this.walletId = walletId;
    }

    @Override public PaymentResult process(IdempotencyKey key, PaymentProcessor.Context ctx){
        double balance = WALLET_BALANCES.getOrDefault(walletId, 0.0);
        double discounted = ctx.promo.apply(amount);
        double fee = ctx.fees.apply(discounted, this);
        double charge = round2(discounted + fee);
        if(balance < charge) return new PaymentResult(transactionId, Status.FAILED, 0.0,
                "Insufficient wallet balance");
        WALLET_BALANCES.put(walletId, round2(balance - charge));
        ctx.persistSuccess(this, charge, fee, amount - discounted, key);
        ctx.notify(this, charge, fee, amount - discounted, Status.SUCCESS);
        return new PaymentResult(transactionId, Status.SUCCESS, charge, "Wallet charged");
    }

    @Override public RefundResult refund(double amt, PaymentProcessor.Context ctx){
        RefundResult res = ctx.performRefund(transactionId, amt, userId);
        if(res.status() == Status.SUCCESS){
            WALLET_BALANCES.merge(walletId, res.refundedAmount(), Double::sum);
        }
        return res;
    }

    @Override public String getMaskedInfo(){
        String tail = walletId.length() <= 4 ? walletId : walletId.substring(walletId.length()-4);
        return "WALLET-****" + tail;
    }

    @Override public String methodName(){ return "Wallet"; }
}

// ======= Payment Processor =======
final class PaymentProcessor {
    static final class Context {
        final TransactionRepository repo; final FeeStrategy fees; final Promo promo; final Notifier notifier;
        Context(TransactionRepository r, FeeStrategy f, Promo p, Notifier n){ repo=r; fees=f; promo=p; notifier=n; }
        void persistSuccess(Payment p, double charged, double fee, double discount, IdempotencyKey key){
            var tx = new Transaction(p.transactionId, p.userId, p.methodName(), p.currency,
                    p.amount, charged, p.getMaskedInfo(), Status.SUCCESS, Instant.now(), key);
            repo.save(tx);
        }
        void notify(Payment p, double charged, double fee, double discount, Status status){
            var receipt = new Receipt(p.transactionId, p.userId, p.methodName(), p.getMaskedInfo(),
                    p.amount, charged, fee, discount, status, Instant.now());
            notifier.notify(p.userId, receipt);
        }
        RefundResult performRefund(String transactionId, double amt, String userId){
            if(amt <= 0) return new RefundResult("", Status.FAILED, 0.0, "Refund must be > 0");
            var txOpt = repo.findById(transactionId);
            if(txOpt.isEmpty()) return new RefundResult("", Status.FAILED, 0.0, "Txn not found");
            var tx = txOpt.get();
            double remaining = round2(tx.capturedAmount - tx.totalRefunded);
            if(amt > remaining) return new RefundResult("", Status.FAILED, 0.0, "Refund exceeds remaining");
            tx.totalRefunded = round2(tx.totalRefunded + amt);
            String refundId = "R-" + transactionId;
            notifier.notify(userId, new Receipt(transactionId, userId, tx.methodName, tx.maskedInfo,
                    tx.originalAmount, -amt, 0.0, 0.0, Status.SUCCESS, Instant.now()));
            return new RefundResult(refundId, Status.SUCCESS, amt, "Refunded");
        }
        private static double round2(double v){ return Math.round(v * 100.0)/100.0; }
    }

    private final TransactionRepository repo; private final FeeStrategy fees; private final Promo promo; private final Notifier notifier;
    public PaymentProcessor(TransactionRepository repo, FeeStrategy fees, Promo promo, Notifier notifier){
        this.repo = repo; this.fees = fees; this.promo = promo; this.notifier = notifier;
    }

    public PaymentResult execute(Payment payment, IdempotencyKey key){
        if(key != null){
            var existing = repo.findByIdempotency(key);
            if(existing.isPresent()){
                var ex = existing.get();
                return new PaymentResult(ex.id, ex.status, ex.capturedAmount, "Idempotent replay");
            }
        }
        var ctx = new Context(repo, fees, promo, notifier);
        return payment.process(key, ctx);
    }

    public RefundResult refund(String transactionId, double amt){
        var ctx = new Context(repo, fees, promo, notifier);
        return ctx.performRefund(transactionId, amt, "");
    }
}

// ======= Factory (Creation Encapsulation) =======
record PaymentRequest(String method, double amount, Currency currency, Map<String,String> details, String userId) {}

final class PaymentFactory {
    private final Map<String, Function<PaymentRequest, Payment>> registry = new HashMap<>();
    public PaymentFactory register(String method, Function<PaymentRequest, Payment> ctor){
        registry.put(method.toLowerCase(Locale.ROOT), ctor); return this;
    }
    public Payment create(PaymentRequest req){
        Function<PaymentRequest, Payment> fn = registry.get(req.method().toLowerCase(Locale.ROOT));
        if(fn == null) throw new IllegalArgumentException("Unknown method: "+req.method());
        return fn.apply(req);
    }
}

// ======= Provider Failure Simulation =======
final class ProviderRandom {
    private static final Random R = new Random(42);
    static boolean maybeFail(){ return R.nextDouble() < 0.05; } // 5% hiccup
}
static boolean providerHiccup(){ return ProviderRandom.maybeFail(); }

// ======= Demo, Tests & CLI =======
public class PaymentGatewayDemo {
    public static void main(String[] args){
        if(args.length>0 && args[0].equals("test")) { TestRunner.runAll(); return; }
        if(args.length>0 && args[0].equals("demo")) { demo(); return; }
        System.out.println("Usage: java PaymentGatewayDemo [demo|test]");
    }

    static void demo(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy();
        fees.register(CreditCardPayment.class, 2.0);
        fees.register(UPIPayment.class, 0.5);
        fees.register(WalletPayment.class, 1.0);
        Promo promo = new PercentagePromo(10.0); // 10% off
        Notifier notifier = new EmailNotifier();
        var processor = new PaymentProcessor(repo, fees, promo, notifier);

        var factory = new PaymentFactory()
                .register("card", req -> new CreditCardPayment(newTxnId(), req.amount(), req.currency(), req.userId(),
                        req.details().getOrDefault("name",""), req.details().get("cardNumber"),
                        YearMonth.parse(req.details().get("expiry"), DateTimeFormatter.ofPattern("MM/yyyy")),
                        req.details().get("cvv")))
                .register("upi", req -> new UPIPayment(newTxnId(), req.amount(), req.currency(), req.userId(),
                        req.details().get("upiId")))
                .register("wallet", req -> new WalletPayment(newTxnId(), req.amount(), req.currency(), req.userId(),
                        req.details().get("walletId")));

        // Wallet top-up and run the sample stories
        WalletPayment.topUp("wal-001", 5000);

        // 1) Card payment ₹2000
        var cardReq = new PaymentRequest("card", 2000, Currency.INR, Map.of(
                "name","Ayush", "cardNumber","4111111111111111", "expiry","12/2030", "cvv","123"), "ayush");
        var r1 = processor.execute(factory.create(cardReq), new IdempotencyKey("k1"));
        System.out.println("Payment " + r1.status() + " txn="+r1.transactionId()+" charged=\u20B9" + r1.chargedAmount());

        // 2) UPI invalid
        try {
            var badUpi = new PaymentRequest("upi", 1200, Currency.INR, Map.of("upiId","badupi@xx@yy"), "ayush");
            processor.execute(factory.create(badUpi), new IdempotencyKey("k2"));
        } catch(Exception ex){ System.out.println("UPI validation failed: " + ex.getMessage()); }

        // 3) Wallet low balance then success after top-up
        var walletReq = new PaymentRequest("wallet", 6000, Currency.INR, Map.of("walletId","wal-001"), "ayush");
        var r3 = processor.execute(factory.create(walletReq), new IdempotencyKey("k3"));
        System.out.println("Wallet attempt: " + r3.message());
        WalletPayment.topUp("wal-001", 5000);
        r3 = processor.execute(factory.create(walletReq), new IdempotencyKey("k3")); // same key → idempotent
        System.out.println("Wallet attempt after topup (idempotent): " + r3.message());

        // 4) Partial refund 500 of first txn
        var refund = processor.refund(r1.transactionId(), 500);
        System.out.println("Refund " + refund.status() + " refundId=" + refund.refundId() + " amount=\u20B9" + refund.refundedAmount());

        // 5) Over-refund should fail
        var refund2 = processor.refund(r1.transactionId(), 2000);
        System.out.println("Second refund: " + refund2.message());

        // 6) History
        System.out.println("History for ayush:");
        for(var tx : repo.findByUser("ayush")){
            System.out.println("["+tx.id+" " + tx.status + " \u20B9" + tx.capturedAmount + "] " + tx.methodName + "(" + tx.maskedInfo + ")");
        }
    }

    static String newTxnId(){ return "TXN-" + UUID.randomUUID().toString().substring(0,8).toUpperCase(); }
}

// ======= Minimal Unit Tests (no frameworks) =======
final class TestRunner {
    static void runAll(){
        int pass=0, fail=0;
        try { testCardHappyPath(); pass++; } catch(Throwable t){ fail("testCardHappyPath", t); }
        try { testUPIValidation(); pass++; } catch(Throwable t){ fail("testUPIValidation", t); }
        try { testWalletBalanceAndRefunds(); pass++; } catch(Throwable t){ fail("testWalletBalanceAndRefunds", t); }
        try { testPolymorphicProcessing(); pass++; } catch(Throwable t){ fail("testPolymorphicProcessing", t); }
        try { testIdempotency(); pass++; } catch(Throwable t){ fail("testIdempotency", t); }
        System.out.println("TESTS: " + pass + " passed, " + fail + " failed");
    }

    static void testCardHappyPath(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy(); fees.register(CreditCardPayment.class, 2.0);
        var proc = new PaymentProcessor(repo, fees, new NoPromo(), new SMSNotifier());
        var factory = new PaymentFactory().register("card", req -> new CreditCardPayment("TXN-TEST1", req.amount(), req.currency(), req.userId(),
                "A", "4111111111111111", YearMonth.now().plusMonths(1), "123"));
        var res = proc.execute(factory.create(new PaymentRequest("card", 1000, Currency.INR, Map.of(), "u1")), new IdempotencyKey("kT1"));
        assert res.status()==Status.SUCCESS : "Expected success";
        // fee 2% on 1000 = 20; charge 1020
        assert Math.abs(res.chargedAmount()-1020.0) < 0.01 : "Wrong charge";
    }

    static void testUPIValidation(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy(); fees.register(UPIPayment.class, 0.5);
        var proc = new PaymentProcessor(repo, fees, new NoPromo(), new EmailNotifier());
        var factory = new PaymentFactory().
                register("upi", req -> new UPIPayment("TXN-TEST2", req.amount(), req.currency(), req.userId(), req.details().get("upiId")));
        boolean threw = false;
        try {
            proc.execute(factory.create(new PaymentRequest("upi", 500, Currency.INR, Map.of("upiId","bad@xx@yy"), "u2")), new IdempotencyKey("kT2"));
        } catch(IllegalArgumentException ex){ threw = true; }
        assert threw : "UPI should have failed validation";
    }

    static void testWalletBalanceAndRefunds(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy(); fees.register(WalletPayment.class, 1.0);
        var proc = new PaymentProcessor(repo, fees, new NoPromo(), new EmailNotifier());
        WalletPayment.topUp("w1", 1000);
        var factory = new PaymentFactory().register("wallet", req -> new WalletPayment("TXN-TEST3", req.amount(), req.currency(), req.userId(), req.details().get("walletId")));
        var res = proc.execute(factory.create(new PaymentRequest("wallet", 990, Currency.INR, Map.of("walletId","w1"), "u3")), new IdempotencyKey("kT3"));
        assert res.status()==Status.SUCCESS : "Wallet pay should succeed";
        var r1 = proc.refund("TXN-TEST3", 500);
        assert r1.status()==Status.SUCCESS : "Refund should succeed";
        var r2 = proc.refund("TXN-TEST3", 600);
        assert r2.status()==Status.FAILED : "Over-refund should fail";
    }

    static void testPolymorphicProcessing(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy();
        fees.register(CreditCardPayment.class, 2.0);
        fees.register(UPIPayment.class, 0.5);
        fees.register(WalletPayment.class, 1.0);
        var proc = new PaymentProcessor(repo, fees, new PercentagePromo(10.0), new SMSNotifier());
        WalletPayment.topUp("w2", 10000);
        var factory = new PaymentFactory()
                .register("card", req -> new CreditCardPayment("TXN-P1", req.amount(), req.currency(), req.userId(),
                        "A", "4111111111111111", YearMonth.now().plusYears(1), "123"))
                .register("upi", req -> new UPIPayment("TXN-P2", req.amount(), req.currency(), req.userId(), req.details().get("upiId")))
                .register("wallet", req -> new WalletPayment("TXN-P3", req.amount(), req.currency(), req.userId(), req.details().get("walletId")));

        List<Payment> batch = List.of(
                factory.create(new PaymentRequest("card", 1000, Currency.INR, Map.of(), "u4")),
                factory.create(new PaymentRequest("upi", 1000, Currency.INR, Map.of("upiId","john@okicici"), "u4")),
                factory.create(new PaymentRequest("wallet", 1000, Currency.INR, Map.of("walletId","w2"), "u4"))
        );
        var keyBase = "poly-" + System.nanoTime();
        for(int i=0;i<batch.size();i++){
            var res = proc.execute(batch.get(i), new IdempotencyKey(keyBase+"-"+i));
            assert res.status()==Status.SUCCESS : "Polymorphic payment failed";
        }
    }

    static void testIdempotency(){
        var repo = new InMemoryTransactionRepository();
        var fees = new RegistryFeeStrategy(); fees.register(UPIPayment.class, 0.5);
        var proc = new PaymentProcessor(repo, fees, new NoPromo(), new EmailNotifier());
        var factory = new PaymentFactory().register("upi", req -> new UPIPayment("TXN-IDE", req.amount(), req.currency(), req.userId(), req.details().get("upiId")));
        var key = new IdempotencyKey("same-key");
        var req = new PaymentRequest("upi", 1000, Currency.INR, Map.of("upiId","ayush@oksbi"), "u5");
        var first = proc.execute(factory.create(req), key);
        var again = proc.execute(factory.create(req), key);
        assert again.message().contains("Idempotent");
        assert Math.abs(first.chargedAmount()-again.chargedAmount()) < 0.0001;
    }

    static void fail(String name, Throwable t){ System.out.println("FAIL " + name + ": " + t); }
}
