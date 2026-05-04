What is this project  actually?
At its core  this project is a Distributed Background Job Processor.
In software development  you never want your users to wait on a loading screen while your server does heavy  slow work. This project solves that problem. It is an architectural  middleman  that takes heavy tasks from your main application  stores them safely  and processes them in the background at its own pace.
A simple analogy: Imagine you run a busy restaurant:
Your API (TaskController) is the Waiter: The waiter's only job is to take the customer's order  say  Got it!   and immediately go help the next customer. They do not cook the food.
Redis is the Ticket Rail: The waiter pins the order ticket to the rail in the kitchen.
Your Java Workers (RedisQueueWorker) are the Chefs: You have 10 chefs (your multi-threaded worker pool). They constantly look at the ticket rail  grab the next ticket  and do the heavy work (cooking) in the background.
PostgreSQL is the Manager's Logbook: It keeps a permanent record of every task (Pending  Processing  Success  Failed).
The Retry & Watchdog System: If a chef accidentally drops a steak  the manager notices  puts a new ticket back on the rail  and tells them to try again (Exponential Backoff Retry). If it fails 3 times  it gets thrown in the trash bin (Dead Letter Queue).

What are the real-world use cases?
If you were building a massive application  you would use this exact system for:
Video/Image Processing (Like YouTube or Instagram): When a user uploads a 4GB video  you can't make them keep the webpage open while you compress it. You instantly say  Upload Complete!  and send a ProcessVideo task to this queue. The workers compress it in the background and notify the user when it's done.
Sending Bulk Emails/Notifications: If you have 100 000 users and need to send a newsletter  doing it in a simple loop would crash your server. Instead  you dump 100 000 tasks into this queue. Your workers will send them out smoothly at a safe speed.
Generating Heavy Reports (Fintech/Banks): When a user clicks  Download 5-Year Bank Statement   it might take the database 30 seconds to generate the PDF. Instead of freezing the website  you give the task to the queue and email the user the PDF when it's ready.
Interacting with Unreliable External APIs: Say your app charges credit cards via Stripe  but Stripe's servers go down for 5 minutes. Without a queue  the user's payment fails and they get angry. With your queue  the payment task simply fails  waits a few minutes (exponential backoff)  and tries again automatically when Stripe comes back online.



```mermaid
graph TD
    %% Styling Classes
    classDef client fill:#f3f4f6,stroke:#374151,stroke-width:2px;
    classDef api fill:#bfdbfe,stroke:#2563eb,stroke-width:2px;
    classDef redis fill:#fecaca,stroke:#dc2626,stroke-width:2px;
    classDef db fill:#bbf7d0,stroke:#16a34a,stroke-width:2px;
    classDef worker fill:#fef08a,stroke:#ca8a04,stroke-width:2px;
    classDef scheduler fill:#e5e7eb,stroke:#4b5563,stroke-width:2px,stroke-dasharray: 5 5;

    Client["📱 Client / User"]:::client
    API["🌐 TaskController<br/>(Spring Boot REST API)"]:::api
    DB[("🗄️ PostgreSQL<br/>(tasks table)")]:::db

    subgraph "🔴 Redis Message Broker"
        TQ["task_queue<br/>(List)"]:::redis
        PQ["processing_queue<br/>(List)"]:::redis
        DQ["delayed_retry_queue<br/>(Sorted Set)"]:::redis
        DLQ["dead_letter_queue<br/>(List)"]:::redis
    end

    subgraph "⚙️ Worker Node (Multi-Threaded)"
        Worker["RedisQueueWorker<br/>(10 Concurrent Threads)"]:::worker
    end

    subgraph "⏱️ Background Schedulers"
        Watchdog["WatchdogService<br/>(Runs every 1 min)"]:::scheduler
        DelayPoller["DelayedQueueScheduler<br/>(Runs every 1 sec)"]:::scheduler
    end

    %% API Flow
    Client -- "1. POST /tasks" --> API
    API -- "2. Insert ID & Payload<br/>(Status: PENDING)" --> DB
    API -- "3. LPUSH Task ID" --> TQ

    %% Worker Flow
    Worker -- "4. BRPOPLPUSH<br/>(Atomic pop & push)" --> TQ
    TQ -.-> PQ
    Worker -- "5. Update DB<br/>(Status: PROCESSING)" --> DB
    
    %% Success Flow
    Worker -- "6a. Success: Remove ID" --> PQ
    Worker -- "6b. Update DB<br/>(Status: SUCCESS)" --> DB

    %% Failure Flow (Retry)
    Worker -- "7a. Failure: Retries < 3?<br/>ZADD with Future Timestamp" --> DQ
    Worker -- "7b. Update DB<br/>(Status: RETRYING)" --> DB
    Worker -- "7c. Remove ID" --> PQ

    %% Failure Flow (DLQ)
    Worker -- "8a. Max Retries Reached?<br/>LPUSH to Dead Letters" --> DLQ
    Worker -- "8b. Update DB<br/>(Status: FAILED)" --> DB

    %% Schedulers Flow
    DelayPoller -- "9. Polls tasks where<br/>timestamp <= NOW" --> DQ
    DelayPoller -- "10. Re-queues task" --> TQ
    
    Watchdog -- "11. Scans for stuck tasks<br/>(> 5 mins old)" --> PQ
    Watchdog -- "12. Re-queues orphaned task" --> TQ
```

