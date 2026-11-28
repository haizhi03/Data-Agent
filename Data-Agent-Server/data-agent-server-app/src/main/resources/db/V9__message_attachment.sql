ALTER TABLE ai_stored_chat_message
    ADD COLUMN IF NOT EXISTS attachment TEXT;
COMMENT ON COLUMN ai_stored_chat_message.attachment IS 'Attachment JSON stored apart from the message text, such as an ER query focus.';
