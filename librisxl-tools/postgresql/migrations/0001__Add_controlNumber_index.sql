CREATE INDEX idx_lddb_controlNumber on lddb ((data#>>'{@graph,0,controlNumber}'));
