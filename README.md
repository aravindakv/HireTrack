# HireTrack AI

HireTrack AI helps job seekers tailor their resume to each job with AI and track every application in one place.

- **AI Resume Match:** paste a job description to get a fit score, the skills you're missing and three tailored resume bullets.
- **Application pipeline:** a Kanban board from Wishlist to Offer, with interview dates.
- **Dashboard:** applications by stage, interviews this week and your response rate.
- **Daily Job Feed:** new postings near you each morning, from the Adzuna API, ranked against your resume.

AI runs on the **OpenAI API** by default, and Anthropic Claude can be switched on with one setting.

## Documentation

| Document | What it covers |
| --- | --- |
| [Product & technical specification](docs/Final-Specification-Doc.md) | Features, rules, data model and API (sections 1–9, the class build), plus the production design (section 10: SLOs, throughput, cost, metrics, idempotency, testing) |
| [Specification with diagrams](docs/HireTrack-Spec.html) | The same spec as a web page, with architecture, job feed, stage-rule and database diagrams |
| [Build guide](docs/implementation/README.md) | A 15-phase, step-by-step guide to building the production version yourself, with complete code, a check after every step and a demo per phase |

## Two builds, one product

| | Class build | Production build |
| --- | --- | --- |
| Stack | React, FastAPI, SQLite | React, 8 Java 25 / Spring Boot 4 microservices, PostgreSQL, Kafka, Redis |
| Runs | One process | Docker Compose at `http://localhost` first ([Phase 12](docs/implementation/12-deploy-local.md)), AWS later ([Phase 13](docs/implementation/13-deploy-aws.md)) |

## Running the production build locally

The application code lives in [`hiretrack/`](hiretrack/). Once you've built it by following the guide:

```bash
cd hiretrack
cp .env.example .env          # add OPENAI_API_KEY, OPENAI_MODEL, OPENAI_FEED_MODEL (and Adzuna keys for the Job Feed)
tools/stack-up.sh             # builds and starts everything
open http://localhost
```

You need Docker, JDK 25 and Node 20, and about 16 GB of RAM. See [Phase 0](docs/implementation/00-setup.md) for the full list.
