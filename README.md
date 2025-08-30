# Payment-Gate-Way-In-Java
Awesome—here’s a single, fully-fleshed scenario you can hand to students that forces them to use Encapsulation, Abstraction, Inheritance, and Polymorphism (plus good design).

Problem: Design an Online Payment Gateway

You’re building a mini Payment Gateway for an e-commerce site. Customers can pay using Credit/Debit Card, UPI, or Wallet. The gateway must be extensible (more methods later), secure (don’t leak sensitive data), and testable.

Goals (map to OOP)

Abstraction: Define a common contract for all payments.

Inheritance: Implement concrete payment types sharing base behavior.

Polymorphism: Process any payment method via the same interface.

Encapsulation: Keep payment details/private fields secure with validation and controlled access.

Functional Requirements

Create a Payment

Inputs: amount, currency, paymentMethod, and method-specific details

Card: cardHolder, cardNumber, expiry, cvv

UPI: upiId

Wallet: walletId

Output: a Transaction with id, status (PENDING, SUCCESS, FAILED), createdAt.

Process a Payment

Call a unified operation (e.g., processPayment()).

Each method has its own rules:

Card: fail if expired; mask number in logs/receipts; simulate bank auth.

UPI: fail if UPI string invalid (name@bank).

Wallet: fail if balance < amount (maintain mock balances).

Refund a Payment

Support full and partial refunds.

Output: Refund with status, refundedAmount, originalTransactionId.

Fees & Discounts

Apply a payment-method specific fee (e.g., Card 2%, UPI 0.5%, Wallet 1%).

Optionally apply a promo (flat or percentage). Fees apply after discount.

Receipts & Notifications

Generate a text receipt with masked sensitive info (e.g., **** **** **** 1234).

Notify user via a pluggable Notifier (e.g., Email/SMS mock).

History

List transactions for a user (in-memory repository is fine).

Idempotency

If the same idempotencyKey is used to process a payment twice, return the original result instead of charging again.

Non-Functional Constraints

No external network calls—simulate providers with stub classes.

No leaking secrets to toString()/logs.

Clean, testable design (DI where helpful).

Favor Open/Closed (easy to add new payment types without editing existing logic).

Domain Model (suggested)
+--------------------+     +---------------------+
| Payment (abstract) |<----| CreditCardPayment   |
| - amount           |     | - cardNumber (priv) |
| - currency         |     | - expiry (priv)     |
| + process()        |     | + process()         |
| + refund()         |     +---------------------+
| + getMaskedInfo()  |<----| UPIPayment          |
+--------------------+     | - upiId (priv)      |
                           | + process()         |
                           +---------------------+
                           | WalletPayment       |
                           | - walletId (priv)   |
                           | + process()         |
                           +---------------------+

+---------------------+    +----------------------+
| PaymentFactory      |    | PaymentProcessor     |
| + create(...)       |    | + execute(Payment)   |
+---------------------+    | + refund(...)        |
                           +----------------------+

+----------------------+   +----------------------+
| FeeStrategy (iface)  |   | Promo (iface)        |
| + fee(amount)        |   | + apply(amount)      |
+----------------------+   +----------------------+

+----------------------+   +----------------------+
| Notifier (iface)     |   | TransactionRepo      |
| + notify(Receipt)    |   | + save/find          |
+----------------------+   +----------------------+

Key Interfaces / Classes (signatures)
// Abstraction
public abstract class Payment {
    protected final String transactionId;
    protected final double amount;
    protected final String currency;

    protected Payment(String transactionId, double amount, String currency) { ... }

    public abstract PaymentResult process(IdempotencyKey key);
    public abstract RefundResult refund(double amountToRefund);

    public abstract String getMaskedInfo(); // for receipts
}

// Encapsulation + Inheritance
public final class CreditCardPayment extends Payment {
    private final String cardHolder;
    private final String cardNumber; // never exposed raw
    private final YearMonth expiry;
    private final String cvv;

    @Override public PaymentResult process(IdempotencyKey key) { ... }
    @Override public RefundResult refund(double amt) { ... }
    @Override public String getMaskedInfo() { /* **** **** **** 1234 */ }
}

public final class UPIPayment extends Payment { /* upiId private, validations */ }
public final class WalletPayment extends Payment { /* walletId private, balance check */ }

// Polymorphism via common type
public final class PaymentProcessor {
    private final TransactionRepository repo;
    private final FeeStrategy feeStrategy; // could be composite by method
    private final Promo promo;
    private final Notifier notifier;

    public PaymentResult execute(Payment payment, IdempotencyKey key) { ... }
    public RefundResult refund(String transactionId, double amt) { ... }
}

// Creation encapsulation
public final class PaymentFactory {
    public Payment create(PaymentRequest req) { ... } // switch on method or use registry
}

// Strategies
public interface FeeStrategy { double apply(double amount, Payment payment); }
public interface Promo { double apply(double amount); }

// Support types
public record PaymentRequest(
    String method, double amount, String currency, Map<String,String> details, String userId) {}

public record PaymentResult(String transactionId, Status status, double chargedAmount, String message) {}
public record RefundResult(String refundId, Status status, double refundedAmount, String message) {}
public record IdempotencyKey(String value) {}

public interface Notifier { void notify(String userId, Receipt receipt); }
public interface TransactionRepository {
    void save(Transaction tx);
    Optional<Transaction> findById(String id);
    Optional<Transaction> findByIdempotency(IdempotencyKey key);
    List<Transaction> findByUser(String userId);
}

Validation Rules & Edge Cases

Amount > 0; currency in {INR, USD} (configurable).

Card: expiry must be ≥ current month; CVV length 3–4; number passes Luhn (optional).

UPI: must match ^[a-zA-Z0-9.\-_]+@[a-zA-Z]+$.

Wallet: reject if balance insufficient; allow top-up (optional).

Idempotency: same key + same request → return stored result.

Refunds: cannot exceed captured amount (track captured, refunded).

Masking: last 4 digits only; never print CVV/UPI full id in logs.

Timeout simulation: randomly fail with a provider error to test robustness.

Required Use Cases (user stories)

As a customer, I can pay ₹2,000 using a card; I get a receipt with masked card.

As a customer, I can pay via UPI; validation fails on badupi@xx@yy.

As a customer, I can pay via Wallet; failure on low balance; success after top-up.

As a customer, I can refund ₹500 of a ₹2,000 charge; second refund of ₹2,000 should fail (exceeds remaining).

As a developer, I can add NetBankingPayment by writing a new class and registering it—without editing PaymentProcessor logic.

Fees & Promo Calculation Order

netPayable = feeStrategy.apply(promo.apply(amount), payment)

Example: amount ₹1000, promo 10% → ₹900, Card fee 2% → charge ₹918.

I/O (CLI demo, not mandatory)
> pay --method card --amount 2000 --currency INR --cardNumber 4111111111111111 --expiry 12/2030 --cvv 123 --name "Ayush"
Payment SUCCESS txn=TXN-001 charged=₹1960.00 method=Card(****1111)

> refund --txn TXN-001 --amount 500
Refund SUCCESS refundId=R-101 amount=₹500.00

> history --user ayush
[TXN-001 SUCCESS ₹1960.00 Card(****1111)]

Deliverables

Design diagram (ASCII/UML ok) and short rationale (how each OOP principle is used).

Code implementing the model above (no frameworks).

Unit tests covering: happy paths, validation failures, idempotency, partial refunds, polymorphic processing (list of Payment objects).

README explaining how to add a new payment method in ≤ 3 steps.

Evaluation Rubric

Abstraction (20%): clean base Payment API; no type checks in processor.

Inheritance (20%): concrete types reuse base/template logic correctly.

Polymorphism (20%): one execute() works for all methods.

Encapsulation (20%): secrets private, masked outputs, validation in setters/constructors.

Extensibility & SOLID (20%): new method added with minimal changes; good separation via strategies/factory/repo.
