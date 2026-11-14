-- Runs once, when the postgres volume is first created.
DO $$
DECLARE
    svc TEXT;
BEGIN
    FOREACH svc IN ARRAY ARRAY['identity','resume','ai','match','application','feed','analytics']
    LOOP
        EXECUTE format('CREATE USER %I WITH PASSWORD %L', svc || '_svc', svc || '_pw');
        EXECUTE format('CREATE SCHEMA %I AUTHORIZATION %I', svc, svc || '_svc');
        EXECUTE format('ALTER ROLE %I SET search_path = %I', svc || '_svc', svc);
    END LOOP;
END $$;

REVOKE CREATE ON SCHEMA public FROM PUBLIC;