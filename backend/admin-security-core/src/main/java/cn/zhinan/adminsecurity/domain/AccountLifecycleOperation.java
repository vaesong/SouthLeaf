package cn.zhinan.adminsecurity.domain;

/** Deliberately small C3 command vocabulary; enable/delete/recovery are separate future slices. */
public enum AccountLifecycleOperation {
  BOOTSTRAP,
  CHANGE_PASSWORD,
  DISABLE_ACCOUNT,
  REPLACE_PERMISSIONS
}
